package us.bringardner.parley.imap.server;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.core.ILogger;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.SaslPrep;
import us.bringardner.parley.net.IConnection;
import us.bringardner.parley.net.server.AbstractCommandProcessor;
import us.bringardner.parley.net.server.ICommand;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.imap.server.ImapCommand.State;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxView;
import us.bringardner.parley.io.IoUtils;

/**
 * One IMAP session (RFC 9051). Like FtpRequestProcessor and
 * Pop3RequestProcessor it runs command classes from
 * {@link ImapCommandFactory}, but it reads the socket itself, because IMAP
 * commands can carry literals of any size.
 * <p>
 * A session speaks IMAP4rev1 until the client sends "ENABLE IMAP4rev2"; after
 * that it follows RFC 9051 (no \Recent, ESEARCH, UTF-8 strings, ...). Sessions
 * that enable neither IMAP4rev2 nor UTF8=ACCEPT never see raw UTF-8: messages
 * with UTF-8 headers are sent as their RFC 6858 surrogates and mailbox names in
 * modified UTF-7.
 */
public class ImapRequestProcessor extends AbstractCommandProcessor implements IMAP {

	private static final long serialVersionUID = 1L;

	/** Principal parameter: the user's maildrop (INBOX) directory, as for POP3. */
	public static final String PARAMETER_MAILDROP = "maildrop";

	public enum LoginResult {
		OK, FAILED, NOT_AUTHORIZED, TLS_REQUIRED, ERROR
	}

	private static final int MAX_LOGIN_ATTEMPTS = 3;
	/** Socket timeout: how often a waiting session checks for autologout and stop. */
	private static final int POLL_INTERVAL = 1000;

	/** The connection is gone (as opposed to a failure in the mail store). */
	static final class ConnectionLostException extends IOException {
		private static final long serialVersionUID = 1L;

		ConnectionLostException(IOException cause) {
			super(cause.getMessage(), cause);
		}
	}

	private volatile State state = State.NOT_AUTHENTICATED;
	private boolean rev2;
	private boolean utf8Accept;
	private transient ImapInput in;
	private transient ImapOutput out;
	private transient ImapCommandReader reader;
	private transient MailStore store;
	private transient MailboxView view;
	private String selectedName;
	private List<Long> savedSearch;
	private int loginAttempts;
	private volatile boolean idling;

	public ImapRequestProcessor() {
		super();
		setCommandFactory(new ImapCommandFactory());
		setName("ImapRequestProcessor");
		setPropertyPrefix("ImapRequestProcessor");
	}

	public ImapServer getImapServer() {
		return (ImapServer) getServer();
	}

	// ------------------------------------------------------------------ session loop

	@Override
	public void run() {
		running = true;
		IConnection con = getConnection();
		try {
			openStreams();
			untagged(OK + " [CAPABILITY " + capabilities() + "] " + getServer().getName() + " IMAP4rev2 server ready");
			flush();
			loop();
		} catch (ConnectionLostException | SocketException e) {
			// the client went away
		} catch (Throwable e) {
			logError("An error occurred in the IMAP session; closing the connection.", e);
		} finally {
			deselect();
			running = false;
			try {
				getServer().removeClient(this);
			} catch (RuntimeException e) {
				// ignore
			}
			IoUtils.closeQuietly(con);
		}
	}

	private void loop() throws IOException {
		while (running && !stopping && state != State.LOGOUT) {
			ImapRequest req;
			try {
				req = reader.read();
			} catch (SocketTimeoutException e) {
				if (!stopping) {
					untagged(BYE + " Autologout; idle for too long");
					flush();
				}
				return;
			} catch (ImapInput.LineTooLongException e) {
				untagged(BAD + " Command line too long");
				flush();
				continue;
			} catch (ImapCommandReader.LiteralTooLargeException e) {
				String msg = e.getMessage();
				if (msg.startsWith("[TOOBIG]")) {
					tagged(e.getTag(), NO, msg);
				} else {
					tagged(e.getTag(), BAD, msg);
				}
				flush();
				continue;
			} catch (ImapParseException e) {
				if (e.getTag() != null) {
					tagged(e.getTag(), BAD, e.getMessage());
				} else {
					untagged(BAD + " " + e.getMessage());
				}
				flush();
				continue;
			}
			if (req == null) {
				return;
			}
			try {
				dispatch(req);
			} finally {
				for (FileSource f : req.getTempFiles()) {
					try {
						f.delete();
					} catch (IOException ignore) {
						// best effort
					}
				}
			}
			flush();
		}
	}

	private void openStreams() throws IOException {
		Socket s = getConnection().getSocket();
		s.setSoTimeout(POLL_INTERVAL);
		InputStream rawIn = s.getInputStream();
		OutputStream rawOut = s.getOutputStream();
		in = new ImapInput(new FilterInputStream(rawIn) {
			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				try {
					return super.read(b, off, len);
				} catch (SocketTimeoutException e) {
					throw e;
				} catch (IOException e) {
					throw new ConnectionLostException(e);
				}
			}
		});
		in.setKeepWaiting(this::keepWaiting);
		out = new ImapOutput(new FilterOutputStream(rawOut) {
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				try {
					out.write(b, off, len);
				} catch (IOException e) {
					throw new ConnectionLostException(e);
				}
			}

			@Override
			public void flush() throws IOException {
				try {
					out.flush();
				} catch (IOException e) {
					throw new ConnectionLostException(e);
				}
			}
		});
		reader = new ImapCommandReader(in, out, () -> getImapServer().getFileSourceFactory().createTempFile("imap", ".lit"));
		reader.setAppendLimit(getImapServer().getAppendLimit());
	}

	/** While waiting for a command: keep waiting unless stopped or idle too long. */
	private boolean keepWaiting() {
		if (stopping || !running) {
			return false;
		}
		if (idling) {
			return false; // IDLE polls for mailbox changes
		}
		return System.currentTimeMillis() - in.getLastReceived() < getImapServer().getAutologout();
	}

	private void dispatch(ImapRequest req) throws IOException {
		if (isDebugEnabled()) {
			logDebug("Received command=" + req.getName());
		}
		ICommand command = getCommandFactory().getCommand(req);
		if (!(command instanceof ImapCommand)) {
			tagged(req.getTag(), BAD, "Unknown command " + sanitize(req.getName()));
			return;
		}
		run((ImapCommand) command, req);
	}

	/** Run a command (also used by UID for its sub-command). */
	public void run(ImapCommand cmd, ImapRequest req) throws IOException {
		if (!cmd.isValidIn(state)) {
			tagged(req.getTag(), BAD, cmd.getName() + " is not valid in the " + stateName() + " state");
			return;
		}
		try {
			cmd.execute(this, req);
		} catch (ConnectionLostException e) {
			throw e;
		} catch (ImapParseException e) {
			tagged(req.getTag(), BAD, e.getMessage());
		} catch (MailStore.StoreException e) {
			no(req, e.getCode(), e.getMessage());
		} catch (IOException e) {
			logError("Error in " + cmd.getName(), e);
			no(req, CODE_UNAVAILABLE, "The mail store can't do that now: " + e.getMessage());
		} catch (RuntimeException e) {
			logError("Error in " + cmd.getName(), e);
			no(req, CODE_SERVERBUG, "Internal error");
		}
	}

	private String stateName() {
		switch (state) {
		case NOT_AUTHENTICATED:
			return "not authenticated";
		case AUTHENTICATED:
			return "authenticated";
		case SELECTED:
			return "selected";
		default:
			return "logout";
		}
	}

	static String sanitize(String text) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length() && sb.length() < 40; i++) {
			char c = text.charAt(i);
			sb.append(c < 32 || c == 127 ? '?' : c);
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ responses

	public ImapOutput getOutput() {
		return out;
	}

	public void untagged(String text) throws IOException {
		out.write("* ").line(text);
	}

	public void tagged(String tag, String status, String text) throws IOException {
		out.write(tag == null ? "*" : tag).write(" ").write(status).write(" ").line(text);
	}

	public void continuation(String text) throws IOException {
		out.line("+ " + text).flush();
	}

	public void flush() throws IOException {
		out.flush();
	}

	/** Send pending updates, then "tag OK text". */
	public void ok(ImapRequest req, String text) throws IOException {
		ok(req, null, text);
	}

	public void ok(ImapRequest req, String code, String text) throws IOException {
		sendUpdates(allowsExpunge(req));
		tagged(req.getTag(), OK, (code == null ? "" : "[" + code + "] ") + text);
	}

	/** Send pending updates, then "tag NO [code] text". */
	public void no(ImapRequest req, String code, String text) throws IOException {
		sendUpdates(allowsExpunge(req));
		tagged(req.getTag(), NO, (code == null ? "" : "[" + code + "] ") + text);
	}

	public void bad(ImapRequest req, String text) throws IOException {
		tagged(req.getTag(), BAD, text);
	}

	private boolean allowsExpunge(ImapRequest req) {
		ICommand c = getCommandFactory().getCommand(req);
		if (c instanceof ImapCommand) {
			return ((ImapCommand) c).allowsExpunge();
		}
		return true;
	}

	/**
	 * Tell the client about changes to the selected mailbox made by others:
	 * EXPUNGE (unless not allowed now), EXISTS and FETCH FLAGS responses.
	 */
	public void sendUpdates(boolean allowExpunge) throws IOException {
		if (view == null) {
			return;
		}
		Mailbox mb = view.getMailbox();
		mb.refresh(false);
		MailboxView.Updates u = view.collectUpdates(allowExpunge);
		for (int msn : u.expunged) {
			untagged(msn + " EXPUNGE");
		}
		if (u.exists >= 0) {
			untagged(u.exists + " EXISTS");
			if (!rev2) {
				untagged("0 RECENT");
			}
		}
		for (long[] f : u.flagsChanged) {
			var m = mb.get(f[1]);
			if (m != null) {
				untagged(f[0] + " FETCH (UID " + f[1] + " FLAGS " + flagList(m.getFlags(mb)) + ")");
			}
		}
		if (allowExpunge && mb.isDeleted()) {
			// the mailbox was deleted or renamed by another session
			untagged(OK + " [" + CODE_CLOSED + "] The mailbox no longer exists");
			deselect();
			state = State.AUTHENTICATED;
		}
	}

	/** "(\Seen \Answered)". */
	public static String flagList(java.util.Collection<String> flags) {
		return "(" + String.join(" ", flags) + ")";
	}

	// ------------------------------------------------------------------ capabilities and modes

	public String capabilities() {
		ImapServer server = getImapServer();
		StringBuilder sb = new StringBuilder(CAP_IMAP4REV1 + " " + CAP_IMAP4REV2);
		if (state == State.NOT_AUTHENTICATED) {
			if (!isTls() && server.isTlsAvailable()) {
				sb.append(" STARTTLS");
			}
			if (isLoginBlockedUntilTls()) {
				sb.append(" LOGINDISABLED");
			} else {
				sb.append(" AUTH=PLAIN SASL-IR");
			}
		}
		sb.append(" LITERAL+ ENABLE IDLE NAMESPACE UNSELECT UIDPLUS ESEARCH SEARCHRES LIST-EXTENDED LIST-STATUS MOVE"
				+ " SPECIAL-USE CHILDREN BINARY STATUS=SIZE " + CAP_UTF8_ACCEPT);
		return sb.toString();
	}

	public State getState() {
		return state;
	}

	/** True after "ENABLE IMAP4rev2". */
	public boolean isRev2() {
		return rev2;
	}

	public void enableRev2() {
		rev2 = true;
	}

	/** True after "ENABLE UTF8=ACCEPT" (RFC 6855). */
	public boolean isUtf8Accept() {
		return utf8Accept;
	}

	public void enableUtf8Accept() {
		utf8Accept = true;
	}

	/** True if strings and messages may contain raw UTF-8 (IMAP4rev2 or UTF8=ACCEPT). */
	public boolean isUtf8() {
		return rev2 || utf8Accept;
	}

	public boolean isTls() {
		IConnection con = getConnection();
		return con != null && con.isSecure();
	}

	public boolean isLoginBlockedUntilTls() {
		return getImapServer().isRequireTls() && !isTls();
	}

	/** STARTTLS: the response has been sent; negotiate TLS and forget anything buffered. */
	public void startTls() throws IOException {
		out.flush();
		getConnection().negotiateSecureSocket("TLS");
		openStreams();
	}

	// ------------------------------------------------------------------ strings and names

	public String string(String s) {
		return ImapStrings.string(s, isUtf8());
	}

	public String nstring(String s) {
		return ImapStrings.nstring(s, isUtf8());
	}

	/** A mailbox name for a response: UTF-8 in UTF-8 sessions, modified UTF-7 otherwise. */
	public String mailboxName(String name) {
		if (isUtf8()) {
			return ImapStrings.astring(name, true);
		}
		return ImapStrings.astring(ModifiedUtf7.encode(name), false);
	}

	/** A mailbox name from a command (modified UTF-7 unless in a UTF-8 session). */
	public String mailboxArg(String raw) {
		String name = isUtf8() ? raw : ModifiedUtf7.decode(raw);
		return MailStore.normalize(name);
	}

	// ------------------------------------------------------------------ login

	/** Log in (LOGIN, AUTHENTICATE PLAIN); user name and password are prepared with SASLprep. */
	public LoginResult login(String user, String password) {
		if (isLoginBlockedUntilTls()) {
			return LoginResult.TLS_REQUIRED;
		}
		user = SaslPrep.prepare(user, false);
		password = SaslPrep.prepare(password, false);
		if (user == null || password == null || user.isEmpty()) {
			return LoginResult.FAILED;
		}
		IPrincipal p = getServer().authenticate(user, password.getBytes(StandardCharsets.UTF_8));
		if (p == null) {
			return LoginResult.FAILED;
		}
		if (!getServer().isAuthorized(p, ImapCommand.READ_PERMISSION)) {
			return LoginResult.NOT_AUTHORIZED;
		}
		try {
			store = getImapServer().getMailStore(p);
		} catch (IOException | RuntimeException e) {
			logError("Can't open the mail store of " + p.getName(), e);
			return LoginResult.ERROR;
		}
		setPrincipal(p);
		state = State.AUTHENTICATED;
		return LoginResult.OK;
	}

	/** Reply to a login attempt; after too many failures the connection is closed. */
	public void replyLoginResult(ImapRequest req, LoginResult result) throws IOException {
		switch (result) {
		case OK:
			ok(req, "CAPABILITY " + capabilities(), "Logged in");
			break;
		case TLS_REQUIRED:
			no(req, CODE_PRIVACYREQUIRED, "Use STARTTLS before logging in");
			break;
		case NOT_AUTHORIZED:
			loginFailedDelay();
			no(req, CODE_AUTHORIZATIONFAILED, "Not allowed to use this server");
			break;
		case ERROR:
			no(req, CODE_UNAVAILABLE, "The mail store can't be opened");
			break;
		default:
			loginFailedDelay();
			no(req, CODE_AUTHENTICATIONFAILED, "Invalid user name or password");
			if (++loginAttempts >= MAX_LOGIN_ATTEMPTS) {
				untagged(BYE + " Too many failed logins");
				flush();
				state = State.LOGOUT;
			}
		}
	}

	private void loginFailedDelay() {
		int delay = getImapServer().getLoginFailureDelay();
		if (delay > 0) {
			try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** True if the user may change things (WRITE permission). */
	public boolean canWrite() {
		return isAuthorized(ImapCommand.WRITE_PERMISSION);
	}

	/** Read a line from the client (AUTHENTICATE responses, IDLE's DONE); null at end of stream. */
	public String readLine() throws IOException {
		out.flush();
		byte[] line = in.readLine(64 * 1024);
		return line == null ? null : new String(line, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ mailboxes

	public MailStore getStore() {
		return store;
	}

	public MailboxView getView() {
		return view;
	}

	public String getSelectedName() {
		return selectedName;
	}

	/**
	 * Select a mailbox (SELECT, EXAMINE). A mailbox selected before is closed
	 * first, without expunging.
	 */
	public MailboxView select(String name, boolean readOnly) throws IOException {
		if (view != null) {
			deselect();
			state = State.AUTHENTICATED;
			if (rev2) {
				untagged(OK + " [" + CODE_CLOSED + "] Previous mailbox closed");
			}
		}
		Mailbox mb = store.open(name);
		mb.refresh(true);
		view = mb.newView(readOnly || !canWrite());
		selectedName = name;
		savedSearch = null;
		state = State.SELECTED;
		return view;
	}

	/** Leave the selected state (CLOSE, UNSELECT, end of session). */
	public void deselect() {
		if (view != null) {
			Mailbox mb = view.getMailbox();
			view.close();
			view = null;
			selectedName = null;
			savedSearch = null;
			if (store != null) {
				store.getRegistry().release(mb);
			}
			if (state == State.SELECTED) {
				state = State.AUTHENTICATED;
			}
		}
	}

	public void logout() {
		deselect();
		state = State.LOGOUT;
	}

	/** The saved search result (SEARCHRES "$"), as UIDs; empty if none. */
	public List<Long> getSavedSearch() {
		return savedSearch == null ? List.of() : savedSearch;
	}

	public void setSavedSearch(List<Long> uids) {
		savedSearch = new ArrayList<>(uids);
	}

	/**
	 * The UIDs of the selected messages named by a sequence set, in ascending
	 * order: message sequence numbers, or UIDs for UID commands.
	 *
	 * @throws ImapParseException for a message number that doesn't exist
	 */
	public List<Long> resolve(String set, boolean uid) {
		SequenceSet s = SequenceSet.parse(set);
		List<Long> uids = view.uids();
		List<Long> ret = new ArrayList<>();
		if (s.isSaved()) {
			java.util.Set<Long> saved = new java.util.HashSet<>(getSavedSearch());
			for (Long u : uids) {
				if (saved.contains(u)) {
					ret.add(u);
				}
			}
			return ret;
		}
		if (uid) {
			long star = uids.isEmpty() ? 0 : uids.get(uids.size() - 1);
			for (Long u : uids) {
				if (s.contains(u, star)) {
					ret.add(u);
				}
			}
			if (uids.isEmpty()) {
				return ret;
			}
		} else {
			if (uids.isEmpty() || s.maxExplicit() > uids.size()) {
				throw new ImapParseException("Invalid message sequence number");
			}
			for (int i = 0; i < uids.size(); i++) {
				if (s.contains(i + 1, uids.size())) {
					ret.add(uids.get(i));
				}
			}
		}
		return ret;
	}

	// ------------------------------------------------------------------ IDLE

	/**
	 * IDLE (RFC 9051 section 6.3.13): send updates as they happen until the client
	 * sends DONE.
	 *
	 * @return false if the connection ended
	 */
	public boolean idle(ImapRequest req) throws IOException {
		continuation("idling");
		idling = true;
		long start = System.currentTimeMillis();
		try {
			while (!stopping) {
				byte[] line;
				try {
					line = in.readLine(1024);
				} catch (SocketTimeoutException e) {
					if (System.currentTimeMillis() - start > getImapServer().getAutologout()) {
						untagged(BYE + " Autologout; idle for too long");
						flush();
						state = State.LOGOUT;
						return false;
					}
					if (view != null) {
						sendUpdates(true);
						flush();
					}
					continue;
				}
				if (line == null) {
					return false;
				}
				String s = new String(line, StandardCharsets.US_ASCII).trim();
				if (s.equalsIgnoreCase("DONE")) {
					ok(req, "IDLE terminated");
				} else {
					bad(req, "Expected DONE");
				}
				return true;
			}
			return false;
		} finally {
			idling = false;
		}
	}

	@Override
	public String translateResponseCode(int code) {
		return code == REPLY_500_GENERIC_ERROR ? BAD : OK;
	}

	@Override
	protected void processLine(String line, Map<String, String> cmdUsed) throws IOException {
		throw new UnsupportedOperationException("IMAP sessions read commands themselves");
	}

	@Override
	protected ILogger getLogger(String name) {
		return super.getLogger("ImapRequestProcessor");
	}
}
