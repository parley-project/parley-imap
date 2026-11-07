package us.bringardner.parley.imap.server;

import us.bringardner.parley.io.LineTooLongException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.core.ILogger;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.UserRegistryUnavailableException;
import us.bringardner.parley.mail.server.AbstractMailProcessor;
import us.bringardner.parley.net.capability.CapabilityRegistry;
import us.bringardner.parley.net.server.ConnectionLostException;
import us.bringardner.parley.net.server.ICommand;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.net.server.StateMachine;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.imap.server.ImapCommand.State;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxAcl;
import us.bringardner.parley.mail.store.MailboxView;

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
public class ImapRequestProcessor extends AbstractMailProcessor implements IMAP {

	private static final long serialVersionUID = 1L;

	/** Principal attribute: the user's maildrop (INBOX) directory, as for POP3. */
	public static final String PARAMETER_MAILDROP = "maildrop";

	public enum LoginResult {
		OK, FAILED, TLS_REQUIRED, ERROR,
		/** The user registry can't answer now (its database is down, say): try again later. @since 1.1.0 */
		UNAVAILABLE
	}

	/** Socket timeout: how often a waiting session checks for autologout and stop. */
	private static final int POLL_INTERVAL = 1000;

	/** NOT_AUTHENTICATED, then AUTHENTICATED, then SELECTED while a mailbox is open; LOGOUT ends it (RFC 9051 section 3). */
	private final StateMachine<State> states = new StateMachine<>(State.NOT_AUTHENTICATED)
			.allow(State.NOT_AUTHENTICATED, State.AUTHENTICATED, State.LOGOUT)
			.allow(State.AUTHENTICATED, State.SELECTED, State.LOGOUT)
			.allow(State.SELECTED, State.AUTHENTICATED, State.LOGOUT);
	private boolean rev2;
	private boolean utf8Accept;
	private transient ImapInput in;
	private transient ImapOutput out;
	private transient ImapCommandReader reader;
	private transient MailStore store;
	private transient MailboxView view;
	/** The rights on the selected mailbox, as they were when it was selected (RFC 4314 section 5.2 lets them be kept). */
	private String selectedRights = "";
	/** Why the logged-in user can't read mail (the read permission is missing), or null. */
	private String readMissing;
	/** Other users' stores this session has opened, by user name (null: no such user). */
	private transient Map<String, MailStore> otherStores = new java.util.HashMap<>();
	private String selectedName;
	private List<Long> savedSearch;
	private volatile boolean idling;
	/** The logged-in user's group names (for $group ACL entries), read once a session; null until read. */
	private java.util.Set<String> groupNames;

	public ImapRequestProcessor() {
		super();
		setCommandFactory(new ImapCommandFactory());
		setName("ImapRequestProcessor");
		setPropertyPrefix("parley.mail.imap.session");
	}

	public ImapServer getImapServer() {
		return (ImapServer) getServer();
	}

	// ------------------------------------------------------------------ session loop

	/** The untagged OK greeting with the capabilities. */
	@Override
	protected void sendGreeting() throws IOException {
		openStreams();
		untagged(OK + " [CAPABILITY " + capabilities() + "] " + getServer().getName() + " IMAP4rev2 server ready");
		flush();
	}

	/**
	 * One request, with its literals ({@link ImapCommandReader}). A line or literal that is too
	 * long, or a request that can't be parsed, is answered (BAD, or NO [TOOBIG]) and the next one
	 * read. When the client has been idle longer than the autologout ({@link #isIdleTooLong()}) the
	 * session ends with replyClosing(IDLE), {@code * BYE Autologout}.
	 */
	@Override
	protected IRequestContext readRequest() throws IOException {
		while (true) {
			try {
				return reader.read();
			} catch (SocketTimeoutException e) {
				replyClosing(stopping ? us.bringardner.parley.net.server.CloseReason.SERVER_STOPPING
						: us.bringardner.parley.net.server.CloseReason.IDLE);
				return null;
			} catch (LineTooLongException e) {
				untagged(BAD + " Command line too long");
				flush();
			} catch (ImapCommandReader.LiteralTooLargeException e) {
				String msg = e.getMessage();
				if (msg.startsWith("[TOOBIG]")) {
					tagged(e.getTag(), NO, msg);
				} else {
					tagged(e.getTag(), BAD, msg);
				}
				flush();
			} catch (ImapParseException e) {
				if (e.getTag() != null) {
					tagged(e.getTag(), BAD, e.getMessage());
				} else {
					untagged(BAD + " " + e.getMessage());
				}
				flush();
			}
		}
	}

	/** Run the request, then delete the temporary files its literals were kept in. */
	@Override
	protected void runRequest(IRequestContext request) throws IOException {
		ImapRequest req = (ImapRequest) request;
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
	}

	/** Every request's replies are sent when it is done. */
	@Override
	protected void afterRequest() throws IOException {
		flush();
	}

	/** LOGOUT (or too many failed logins) ends the session. */
	@Override
	protected boolean isSessionEnding() {
		return states.is(State.LOGOUT);
	}

	/** Close the selected mailbox. */
	@Override
	protected void sessionEnded() {
		deselect();
	}

	/** A command failed with an unexpected error (already logged): NO [SERVERBUG] with the request's tag; the session goes on. */
	@Override
	protected void replyCommandFailed(IRequestContext request) throws IOException {
		no((ImapRequest) request, CODE_SERVERBUG, "Internal error");
	}

	/** {@code * BYE}, before the connection is closed after too many failed logins. */
	@Override
	protected void replyTooManyLoginFailures() throws IOException {
		bye("Too many failed logins");
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
		}, getOutputLock());
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
		return !isIdleTooLong(); // the server's limit, set from autologout
	}

	/** The client was last heard from when the reader last received a byte. */
	@Override
	protected long getLastActivityTime() {
		return in == null ? super.getLastActivityTime() : in.getLastReceived();
	}

	/**
	 * Set once a {@code * BYE} has been sent, so the session says goodbye only once (the session and
	 * the server may both try). A flag, not a lock: a virtual thread must not block on the socket
	 * while holding a monitor.
	 */
	private final java.util.concurrent.atomic.AtomicBoolean byeSent = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * The server is closing the session: {@code * BYE} with why (RFC 9051 section 7.1.5), once.
	 * Called by the session itself when it is idle too long (autologout), and by the server when it
	 * stops or the login time limit is reached.
	 */
	@Override
	public void replyClosing(us.bringardner.parley.net.server.CloseReason reason) throws IOException {
		// under the output lock: after any response being written, and BYE's once-only check is one step
		java.util.concurrent.locks.ReentrantLock lock = getOutputLock();
		lock.lock();
		try {
			sayGoodbye(reason);
		} finally {
			lock.unlock();
		}
	}

	private void sayGoodbye(us.bringardner.parley.net.server.CloseReason reason) throws IOException {
		if (out == null) {
			return;
		}
		String text;
		switch (reason) {
			case SERVER_STOPPING:
				text = "Server shutting down";
				break;
			case LOGIN_TIME_LIMIT:
				text = "No login in time";
				break;
			default:
				text = "Autologout; idle for too long";
				break;
		}
		if (bye(text)) {
			flush();
		}
	}

	/**
	 * Send {@code * BYE text}, unless a BYE has already been sent (LOGOUT, the server closing the
	 * session): a session says goodbye once.
	 *
	 * @return true if it was sent
	 */
	public boolean bye(String text) throws IOException {
		if (!byeSent.compareAndSet(false, true)) {
			return false;
		}
		untagged(BYE + " " + text);
		return true;
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
		recordCommandUsed(command.getName());
		run((ImapCommand) command, req);
	}

	/** Run a command (also used by UID for its sub-command). */
	public void run(ImapCommand cmd, ImapRequest req) throws IOException {
		if (!cmd.isValidIn(states.get())) {
			tagged(req.getTag(), BAD, cmd.getName() + " is not valid in the " + stateName() + " state");
			return;
		}
		try {
			if (states.get() != State.NOT_AUTHENTICATED && cmd.requiresAuthorization() && !mayRun(cmd, req)) {
				return;
			}
			cmd.execute(this, req);
		} catch (ConnectionLostException e) {
			throw e;
		} catch (ImapParseException e) {
			tagged(req.getTag(), BAD, e.getMessage());
		} catch (MailStore.StoreException e) {
			no(req, e.getCode(), e.getMessage());
		} catch (UserRegistryUnavailableException e) {
			// a user, group or permission couldn't be read: try again later, never "denied" (RFC 5530)
			logError(cmd.getName() + ": the user registry can't answer: " + e.getMessage());
			no(req, CODE_UNAVAILABLE, "The user registry can't answer now, try again later");
		} catch (IOException e) {
			if (isTlsHandshakeFailure(e)) {
				// STARTTLS: the client's handshake failed, and there's no connection left to answer on;
				// the session loop ends the session and logs the failed handshake (one INFO line)
				throw new ConnectionLostException(e);
			}
			logError("Error in " + cmd.getName(), e);
			no(req, CODE_UNAVAILABLE, "The mail store can't do that now: " + e.getMessage());
		}
		// any other RuntimeException: the session loop logs it and answers replyCommandFailed (NO [SERVERBUG])
	}

	/**
	 * A logged-in command needs its permission (read, for every command that uses mailboxes). Without
	 * it the command gets NO [NOPERM] saying which permission is missing, and the administrators are
	 * told (parley-net's reportMissingPermission).
	 *
	 * @return true when the command may run
	 * @throws UserRegistryUnavailableException when the registry can't answer now (NO [UNAVAILABLE])
	 */
	private boolean mayRun(ImapCommand cmd, ImapRequest req) throws IOException {
		if (holds(cmd.getPermission())) {
			return true;
		}
		no(req, CODE_NOPERM, cmd.getName() + " refused: " + reportMissingPermission(cmd.getName(), cmd.getPermission()));
		return false;
	}

	private String stateName() {
		switch (states.get()) {
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
		if (u.uidValidityChanged) {
			// another server deleted and recreated the mailbox: the UIDs the client has mean
			// nothing now, so close it; selecting it again gives the new UIDVALIDITY
			untagged(OK + " [" + CODE_CLOSED + "] The mailbox was recreated; select it again");
			deselect();
			states.moveTo(State.AUTHENTICATED);
			return;
		}
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
			states.moveTo(State.AUTHENTICATED);
		}
	}

	/** "(\Seen \Answered)". */
	public static String flagList(java.util.Collection<String> flags) {
		return "(" + String.join(" ", flags) + ")";
	}

	// ------------------------------------------------------------------ capabilities and modes

	/**
	 * What this server offers, and when (RFC 9051 section 7.2.2): STARTTLS and the login
	 * capabilities only before login, and LOGINDISABLED instead of AUTH while TLS must come first.
	 */
	private static final CapabilityRegistry CAPABILITIES = new CapabilityRegistry()
			.add(CAP_IMAP4REV1)
			.add(CAP_IMAP4REV2)
			.addWhen(p -> {
				ImapRequestProcessor ip = (ImapRequestProcessor) p;
				return ip.getState() == State.NOT_AUTHENTICATED && !ip.isTls() && ip.getImapServer().isTlsAvailable();
			}, "STARTTLS")
			.addWhen(p -> {
				ImapRequestProcessor ip = (ImapRequestProcessor) p;
				return ip.getState() == State.NOT_AUTHENTICATED && ip.isLoginBlockedUntilTls();
			}, "LOGINDISABLED")
			.addDynamicRequireParams("AUTH", p -> {
				ImapRequestProcessor ip = (ImapRequestProcessor) p;
				boolean open = ip.getState() == State.NOT_AUTHENTICATED && !ip.isLoginBlockedUntilTls();
				return open ? us.bringardner.parley.imap.server.commands.Authenticate.mechanisms(ip) : null;
			})
			.addWhen(p -> {
				ImapRequestProcessor ip = (ImapRequestProcessor) p;
				return ip.getState() == State.NOT_AUTHENTICATED && !ip.isLoginBlockedUntilTls();
			}, "SASL-IR")
			.add("LITERAL+").add("ENABLE").add("IDLE").add("NAMESPACE").add("UNSELECT").add("UIDPLUS")
			.add("ESEARCH").add("SEARCHRES").add("LIST-EXTENDED").add("LIST-STATUS").add("MOVE")
			.add("SPECIAL-USE").add("CHILDREN").add("BINARY").add("STATUS=SIZE")
			.add("ACL").add("RIGHTS=" + MailboxAcl.NEW_RIGHTS)
			.add(CAP_UTF8_ACCEPT);

	/** The capability list as it appears after "CAPABILITY": names separated by spaces. */
	public String capabilities() {
		return CAPABILITIES.resolve(this).toInline();
	}

	public State getState() {
		return states.get();
	}

	@Override
	protected StateMachine<?> getStateMachine() {
		return states;
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

	/** STARTTLS: the response has been sent; negotiate TLS and forget anything buffered. */
	@Override
	protected void beforeTls() throws IOException {
		out.flush();
	}

	@Override
	protected void afterTls() throws IOException {
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

	/**
	 * Log in with a user name and password (LOGIN). Both are prepared with SASLprep and checked by
	 * the server's user registry through the session's {@code authenticate}; a refused login is
	 * counted, and answered late, by parley-net.
	 *
	 * @throws IllegalStateException when the server has no user registry
	 */
	public LoginResult login(String user, String password) {
		if (isLoginBlockedUntilTls()) {
			return LoginResult.TLS_REQUIRED;
		}
		return afterAuthentication(authenticate(user, password), user);
	}

	/**
	 * A SASL authenticator for this session's AUTHENTICATE command (PLAIN, and SCRAM when the user
	 * registry can serve it).
	 *
	 * @since 2.0.0
	 */
	@Override
	public ImapSaslAuthenticator newSaslAuthenticator() {
		return new ImapSaslAuthenticator(this);
	}

	/**
	 * After a login was checked (LOGIN, or a whole AUTHENTICATE exchange): open the mail store when
	 * it was good; a temporary failure when the user registry couldn't answer; otherwise FAILED
	 * (already counted by the session).
	 *
	 * @param result how the login ended
	 * @param user who, or which command, for the log
	 * @since 2.0.0
	 */
	public LoginResult afterAuthentication(AuthenticationResult result, String user) {
		if (result == null) {
			return LoginResult.FAILED;
		}
		switch (result.getStatus()) {
		case AUTHENTICATED:
			return openStore(result.getPrincipal());
		case UNAVAILABLE:
			logError("Can't check the login of " + user + ": " + result.getReason());
			return LoginResult.UNAVAILABLE;
		default:
			return LoginResult.FAILED;
		}
	}

	/**
	 * Check read and open the user's mail store, entering the AUTHENTICATED state. The session is
	 * logged in already; it is logged out again when anything here fails.
	 */
	private LoginResult openStore(ParleyPrincipal p) {
		LoginResult ret = LoginResult.ERROR;
		try {
			ret = openStoreFor(p);
			return ret;
		} finally {
			if (ret != LoginResult.OK) {
				logout();
			}
		}
	}

	private LoginResult openStoreFor(ParleyPrincipal p) {
		if (p == null) {
			return LoginResult.FAILED;
		}
		AuthorizationResult read = checkPermission(ImapCommand.READ_PERMISSION, null);
		if (read.getStatus() == AuthorizationResult.Status.UNAVAILABLE) {
			logError("Can't check the read permission of " + p.getName() + ": " + read.getReason());
			return LoginResult.UNAVAILABLE;
		}
		// without read the login still stands (the password was right): the user is told, the
		// administrators are told, and every command that needs read is refused
		readMissing = read.getStatus() == AuthorizationResult.Status.ALLOWED ? null
				: reportMissingPermission("login", ImapCommand.READ_PERMISSION);
		try {
			store = getImapServer().getMailStore(p);
		} catch (IOException | RuntimeException e) {
			logError("Can't open the mail store of " + p.getName(), e);
			return LoginResult.ERROR;
		}
		groupNames = null;
		otherStores.clear();
		states.moveTo(State.AUTHENTICATED);
		return LoginResult.OK;
	}

	/**
	 * Reply to a login attempt. A refused login has been counted and delayed by the session
	 * already; after too many, the session says {@code * BYE} and closes after this reply.
	 */
	public void replyLoginResult(ImapRequest req, LoginResult result) throws IOException {
		switch (result) {
		case OK:
			ok(req, "CAPABILITY " + capabilities(), readMissing == null ? "Logged in" : "Logged in, but " + readMissing);
			break;
		case TLS_REQUIRED:
			no(req, CODE_PRIVACYREQUIRED, "Use STARTTLS before logging in");
			break;
		case ERROR:
			no(req, CODE_UNAVAILABLE, "The mail store can't be opened");
			break;
		case UNAVAILABLE:
			// not the client's fault: no delay, not counted as a failed login (RFC 5530)
			no(req, CODE_UNAVAILABLE, "Logins can't be checked now, try again later");
			break;
		default:
			no(req, CODE_AUTHENTICATIONFAILED, "Invalid user name or password");
		}
	}

	/**
	 * True when the user registry gives the logged-in user this permission.
	 *
	 * @throws UserRegistryUnavailableException when the registry can't answer now (the command
	 * gets NO [UNAVAILABLE])
	 */
	private boolean holds(ParleyPermission permission) throws UserRegistryUnavailableException {
		AuthorizationResult r = checkPermission(permission, null);
		if (r.getStatus() == AuthorizationResult.Status.UNAVAILABLE) {
			throw new UserRegistryUnavailableException(r.getReason());
		}
		return r.getStatus() == AuthorizationResult.Status.ALLOWED;
	}

	/**
	 * True if the user may change things (the write permission).
	 *
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 */
	public boolean canWrite() throws UserRegistryUnavailableException {
		return holds(ImapCommand.WRITE_PERMISSION);
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

	// ------------------------------------------------------------------ namespaces and rights (RFC 2342, RFC 4314)

	/** The Other Users namespace's prefix, without its delimiter. */
	public static final String OTHER_USERS = "Other Users";

	/**
	 * A mailbox name as the client gave it, and where it is: the store it is in and its name
	 * there. In the Other Users namespace, {@code Other Users/bob@bringardner.us/Projects} is
	 * bob's Projects and {@code Other Users/bob@bringardner.us} bob's INBOX.
	 */
	public static final class MailboxRef {
		/** The name the client uses. */
		public final String name;
		/** The store it is in; null for a user that doesn't exist, or the namespace's own name. */
		public final MailStore store;
		/** Its name in the store. */
		public final String local;
		/** True for the user's own mailboxes (the Personal namespace). */
		public final boolean personal;

		MailboxRef(String name, MailStore store, String local, boolean personal) {
			this.name = name;
			this.store = store;
			this.local = local;
			this.personal = personal;
		}

		/** True if it names a mailbox that can exist (a known user's). */
		public boolean isMailbox() {
			return store != null && local != null;
		}
	}

	/** True if the name is in the Other Users namespace (or is its own name). */
	public static boolean isOtherUsers(String name) {
		return name.equals(OTHER_USERS) || name.startsWith(OTHER_USERS + DELIMITER);
	}

	/** Where a mailbox name (from {@link #mailboxArg(String)}) is. */
	public MailboxRef ref(String name) throws IOException {
		// a personal mailbox made before 2.0.0 under the name "Other Users" stays the user's own
		if (!isOtherUsers(name) || (store != null && store.entry(name) != null)) {
			return new MailboxRef(name, store, name, true);
		}
		String rest = name.length() > OTHER_USERS.length() ? name.substring(OTHER_USERS.length() + 1) : "";
		if (rest.isEmpty()) {
			return new MailboxRef(name, null, null, false);
		}
		int i = rest.indexOf(DELIMITER);
		String user = i < 0 ? rest : rest.substring(0, i);
		String local = i < 0 || i == rest.length() - 1 ? INBOX : MailStore.normalize(rest.substring(i + 1));
		return new MailboxRef(name, otherStore(user), local, false);
	}

	/** The client's name for a mailbox of another user's store. */
	public static String otherName(String owner, String local) {
		return OTHER_USERS + DELIMITER + owner + (INBOX.equals(local) ? "" : DELIMITER + local);
	}

	/** Another user's store (this user's own for its own name), or null if there is no such user. */
	public MailStore otherStore(String user) throws IOException {
		ParleyPrincipal me = getPrincipal();
		if (me != null && me.getName().equals(user)) {
			return store;
		}
		if (otherStores.containsKey(user)) {
			return otherStores.get(user);
		}
		// a registry that can't answer throws UserRegistryUnavailableException: NO [UNAVAILABLE]
		// a group has no store: findUser leaves groups out
		ParleyPrincipal p = getServer().requireUserRegistry().findUser(user);
		MailStore s = p == null ? null : getImapServer().openMailStore(p);
		otherStores.put(user, s);
		return s;
	}

	/**
	 * True if the user may read mail (the read permission).
	 *
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 */
	public boolean canRead() throws UserRegistryUnavailableException {
		return holds(ImapCommand.READ_PERMISSION);
	}

	/**
	 * The names of the logged-in user's groups, for {@code $group} entries in mailbox ACLs:
	 * parley-mail's {@code findGroupNames} (the user registry's {@code findGroups}),
	 * read once a session.
	 *
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 * @since 2.0.0
	 */
	public java.util.Set<String> groupNames() throws UserRegistryUnavailableException {
		if (groupNames == null) {
			groupNames = java.util.Collections.unmodifiableSet(getImapServer().findGroupNames(getPrincipal()));
		}
		return groupNames;
	}

	/**
	 * The user's rights on a mailbox: its ACL's, limited by the server's permissions; "" for a
	 * mailbox that doesn't exist.
	 *
	 * @throws UserRegistryUnavailableException when the user registry can't answer now
	 */
	public String rights(MailboxRef ref) throws IOException {
		if (!ref.isMailbox()) {
			return "";
		}
		ParleyPrincipal me = getPrincipal();
		String r = ref.store.rightsOf(ref.local, me.getName(), groupNames());
		return MailboxAcl.limit(r, canRead(), canWrite());
	}

	/** The rights on the selected mailbox. */
	public String getSelectedRights() {
		return selectedRights;
	}

	/**
	 * Reply NO for a mailbox the user may not use as asked: NONEXISTENT when the user can't see
	 * it (no {@code l}), so its existence isn't told (RFC 4314 section 6), else NOPERM.
	 */
	public void noAccess(ImapRequest r, String rights) throws IOException {
		if (rights.indexOf('l') < 0) {
			no(r, CODE_NONEXISTENT, "No such mailbox");
		} else {
			no(r, CODE_NOPERM, "Permission denied");
		}
	}

	/**
	 * The rights on the nearest existing parent of a mailbox, which CREATE and RENAME's new name
	 * need {@code k} on. A top level name in the Personal namespace has the user's own rights
	 * (k unless the server makes it read only); in another user's, nothing.
	 */
	public String parentRights(MailboxRef ref) throws IOException {
		if (!ref.isMailbox()) {
			return "";
		}
		String name = ref.local;
		int i = name.lastIndexOf(DELIMITER);
		while (i > 0) {
			String parent = name.substring(0, i);
			if (ref.store.entry(parent) != null) {
				return rights(new MailboxRef(parent, ref.store, parent, ref.personal));
			}
			i = parent.lastIndexOf(DELIMITER);
		}
		return ref.personal ? MailboxAcl.limit(MailboxAcl.ALL, canRead(), canWrite()) : "";
	}

	/**
	 * The flags of a change the rights allow (RFC 4314 section 4): {@code s} for \Seen,
	 * {@code t} for \Deleted, {@code w} for every other flag.
	 */
	public static java.util.Set<String> allowedFlags(java.util.Collection<String> flags, String rights) {
		java.util.Set<String> ret = new java.util.LinkedHashSet<>();
		for (String f : flags) {
			if (mayChange(f, rights)) {
				ret.add(f);
			}
		}
		return ret;
	}

	/** True if the rights let the user set or clear this flag. */
	public static boolean mayChange(String flag, String rights) {
		if (flag.equalsIgnoreCase(SEEN)) {
			return rights.indexOf('s') >= 0;
		}
		if (flag.equalsIgnoreCase(DELETED)) {
			return rights.indexOf('t') >= 0;
		}
		return rights.indexOf('w') >= 0;
	}

	/** True if the rights let the user change some flag (READ-WRITE, RFC 4314 section 5.2). */
	public static boolean writable(String rights) {
		return MailboxAcl.hasAny(rights, "iewst");
	}

	/**
	 * Select a mailbox (SELECT, EXAMINE). A mailbox selected before is closed
	 * first, without expunging.
	 */
	public MailboxView select(String name, boolean readOnly) throws IOException {
		MailboxRef ref = ref(name);
		return select(ref, rights(ref), readOnly);
	}

	/**
	 * Select a mailbox the user has {@code r} on. It is read only for EXAMINE, and when the
	 * rights allow no change.
	 * @since 2.0.0
	 */
	public MailboxView select(MailboxRef ref, String rights, boolean readOnly) throws IOException {
		if (view != null) {
			deselect();
			states.moveTo(State.AUTHENTICATED);
			if (rev2) {
				untagged(OK + " [" + CODE_CLOSED + "] Previous mailbox closed");
			}
		}
		if (!ref.isMailbox()) {
			throw new MailStore.StoreException(CODE_NONEXISTENT, "No such mailbox");
		}
		Mailbox mb = ref.store.open(ref.local);
		mb.refresh(true);
		view = mb.newView(readOnly || !writable(rights));
		selectedRights = rights;
		String name = ref.name;
		selectedName = name;
		savedSearch = null;
		states.moveTo(State.SELECTED);
		return view;
	}

	/** Leave the selected state (CLOSE, UNSELECT, end of session). */
	public void deselect() {
		if (view != null) {
			Mailbox mb = view.getMailbox();
			view.close();
			view = null;
			selectedName = null;
			selectedRights = "";
			savedSearch = null;
			if (store != null) {
				store.getRegistry().release(mb);
			}
			if (states.is(State.SELECTED)) {
				states.moveTo(State.AUTHENTICATED);
			}
		}
	}

	/**
	 * End the session (LOGOUT, too many failed logins): close the selected mailbox, log the user out
	 * and enter the LOGOUT state.
	 *
	 * @since 2.0.0 (was logout(); parley-net's session now has its own final logout())
	 */
	public void endSession() {
		deselect();
		logout();
		groupNames = null;
		states.moveTo(State.LOGOUT);
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
		try {
			while (!stopping) {
				byte[] line;
				try {
					line = in.readLine(1024);
				} catch (SocketTimeoutException e) {
					if (isIdleTooLong()) {
						// RFC 9051 section 6.3.13: an idling client is still logged out after autologout
						replyClosing(us.bringardner.parley.net.server.CloseReason.IDLE);
						states.moveTo(State.LOGOUT);
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
	protected ILogger getLogger(String name) {
		return super.getLogger("ImapRequestProcessor");
	}
}
