package us.bringardner.parley.imap.client;

import us.bringardner.parley.mail.Sasl;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import javax.net.ssl.TrustManager;

import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.mail.QuotedPrintable;
import us.bringardner.parley.net.client.Client;
import us.bringardner.parley.net.client.DynamicTrustManager;
import us.bringardner.parley.imap.server.ImapInput;
import us.bringardner.parley.imap.server.ModifiedUtf7;
import us.bringardner.parley.core.NamedThreadFactory;
import us.bringardner.parley.io.IoUtils;

/**
 * An IMAP client (IMAP4rev2, RFC 9051, and IMAP4rev1, RFC 3501), designed to be
 * the mail engine of a desktop application:
 * <ul>
 * <li><b>Thread safe:</b> any thread may call any method; commands run one at a
 * time. {@link #submit(ImapTask)} runs work on the client's own thread and
 * returns a {@link CompletableFuture}, so a user interface never blocks.</li>
 * <li><b>Live updates:</b> {@link ImapListener}s hear about new mail, expunges
 * and flag changes (from IDLE or from any command's responses) on an executor
 * you choose, e.g. {@code SwingUtilities::invokeLater}.</li>
 * <li><b>Any message size:</b> bodies and attachments are streamed to an
 * {@link OutputStream} with {@link ProgressListener} callbacks, never held in
 * memory.</li>
 * <li><b>BJL framework:</b> connections, TLS and certificate trust come from
 * the framework's {@link Client} and {@link DynamicTrustManager}.</li>
 * </ul>
 * Messages are addressed by UID throughout.
 */
public class ImapClient implements AutoCloseable {

	/** Connection states (RFC 9051 section 3). */
	public enum State {
		DISCONNECTED, NOT_AUTHENTICATED, AUTHENTICATED, SELECTED
	}

	/** Work run by {@link #submit(ImapTask)}. */
	@FunctionalInterface
	public interface ImapTask<T> {
		T run(ImapClient client) throws Exception;
	}

	/** The result of COPY or MOVE: UID validity of the target and the new UIDs (COPYUID, RFC 4315). */
	public static final class CopyResult {
		public final long uidValidity;
		/** Source UID to new UID; empty if the server doesn't report them. */
		public final Map<Long, Long> uids;

		CopyResult(long uidValidity, Map<Long, Long> uids) {
			this.uidValidity = uidValidity;
			this.uids = Collections.unmodifiableMap(uids);
		}
	}

	/** Responses of one command. */
	static final class Result {
		final List<Response> untagged = new ArrayList<>();
		Response tagged;
		final List<Response> codes = new ArrayList<>();
	}

	private static final DateTimeFormatter INTERNALDATE = new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendPattern("d-MMM-yyyy HH:mm:ss Z").toFormatter(Locale.US);
	private static final DateTimeFormatter APPEND_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.US);
	private static final int POLL_MS = 1000;

	private final ImapClientConfig config;
	private final ReentrantLock lock = new ReentrantLock(true);
	private final Object writeLock = new Object();
	private final AtomicInteger tags = new AtomicInteger();
	private final List<ImapListener> listeners = new CopyOnWriteArrayList<>();
	private final Executor events;
	private ExecutorService ownEvents;
	private ExecutorService worker;

	private Client connection;
	private ImapInput input;
	private OutputStream output;
	private ResponseReader reader;
	private volatile State state = State.DISCONNECTED;
	private volatile Set<String> capabilities = Collections.emptySet();
	private final Set<String> enabled = Collections.synchronizedSet(new LinkedHashSet<>());
	private volatile SelectedMailbox selected;
	private volatile Consumer<String> trace;
	private volatile long waitStart;
	private volatile String currentCommand = "";
	private final AtomicBoolean disconnectedFired = new AtomicBoolean();

	// IDLE
	private volatile boolean idleMode;
	private volatile boolean idleStopRequested;
	private final AtomicBoolean doneSent = new AtomicBoolean();
	private volatile String idleTag;
	private volatile long idleStarted;
	private Thread idleThread;
	private Thread poller;
	private volatile boolean autoIdle;
	private volatile boolean wantIdle;

	public ImapClient(ImapClientConfig config) {
		this.config = config;
		if (config.getEventExecutor() != null) {
			events = config.getEventExecutor();
		} else {
			ownEvents = Executors.newSingleThreadExecutor(new NamedThreadFactory("ImapClient-events"));
			events = ownEvents;
		}
	}

	public ImapClientConfig getConfig() {
		return config;
	}

	public void addListener(ImapListener l) {
		listeners.add(l);
	}

	public void removeListener(ImapListener l) {
		listeners.remove(l);
	}

	/** See the protocol: lines sent ("C: ") and received ("S: "); passwords are hidden. */
	public void setTrace(Consumer<String> trace) {
		this.trace = trace;
	}

	public State getState() {
		return state;
	}

	public boolean isConnected() {
		return state != State.DISCONNECTED;
	}

	/** Capabilities, in upper case. */
	public Set<String> getCapabilities() {
		return capabilities;
	}

	public boolean hasCapability(String cap) {
		return capabilities.contains(cap.toUpperCase(Locale.ROOT));
	}

	/** True after ENABLE IMAP4rev2. */
	public boolean isRev2() {
		return enabled.contains("IMAP4REV2");
	}

	/** True if strings and mailbox names are UTF-8 (IMAP4rev2 or UTF8=ACCEPT). */
	public boolean isUtf8() {
		return enabled.contains("IMAP4REV2") || enabled.contains("UTF8=ACCEPT");
	}

	/** The selected mailbox, or null. */
	public SelectedMailbox getSelected() {
		return selected;
	}

	// ------------------------------------------------------------------ async

	/**
	 * Run work on the client's worker thread (one task at a time, in order). For
	 * a user interface: {@code client.submit(c -> c.fetchSummaries("1:*")).thenAccept(...)}.
	 */
	public synchronized <T> CompletableFuture<T> submit(ImapTask<T> task) {
		if (worker == null) {
			worker = Executors.newSingleThreadExecutor(new NamedThreadFactory("ImapClient-worker"));
		}
		CompletableFuture<T> f = new CompletableFuture<>();
		worker.execute(() -> {
			try {
				f.complete(task.run(this));
			} catch (Throwable e) {
				f.completeExceptionally(e);
			}
		});
		return f;
	}

	// ------------------------------------------------------------------ connection

	/** Connect (and STARTTLS if configured), reading the greeting and capabilities. */
	public void connect() throws IOException {
		lock.lock();
		try {
			if (state != State.DISCONNECTED) {
				throw new IllegalStateException("Already connected");
			}
			Client c = new Client(config.getHost(), config.getPort());
			c.setConnectTimeout(config.getConnectTimeout());
			if (config.isTrustAllCertificates()) {
				c.setTrustAllCertificates(true);
			} else if (config.getCertificateValidator() != null) {
				SecureBaseObject ctx = new SecureBaseObject();
				ctx.setTrustManagers(new TrustManager[] {new DynamicTrustManager(null, config.getCertificateValidator())});
				c.setContext(ctx);
			}
			if (config.getSecurity() == ImapClientConfig.Security.TLS) {
				c.setSocketFactory(c.getSSLContext("TLS").getSocketFactory());
			}
			if (!c.connect()) {
				IOException e = c.getLastConnectError();
				throw e != null ? e : new IOException("Can't connect to " + config.getHost() + ":" + config.getPort());
			}
			connection = c;
			disconnectedFired.set(false);
			openStreams();
			waitStart = System.currentTimeMillis();
			Response greeting = readResponse();
			if (greeting == null || !greeting.isStatus() || "BYE".equals(greeting.status)) {
				closeSocket();
				throw new IOException("The server refused the connection: " + greeting);
			}
			state = "PREAUTH".equals(greeting.status) ? State.AUTHENTICATED : State.NOT_AUTHENTICATED;
			handleCodes(greeting, null);
			if (capabilities.isEmpty()) {
				capability();
			}
			ImapClientConfig.Security sec = config.getSecurity();
			boolean starttls = sec == ImapClientConfig.Security.STARTTLS
					|| (sec == ImapClientConfig.Security.STARTTLS_IF_AVAILABLE && hasCapability("STARTTLS"));
			if (starttls) {
				if (!hasCapability("STARTTLS")) {
					closeSocket();
					throw new IOException("The server doesn't offer STARTTLS");
				}
				run(new Command("STARTTLS", false));
				connection.negotiateSecureSocket("TLS");
				openStreams();
				capabilities = Collections.emptySet();
				capability(); // RFC 9051 section 6.2.1: capabilities may differ after TLS
			}
		} catch (IOException | RuntimeException e) {
			if (connection != null) {
				closeSocket();
			}
			state = State.DISCONNECTED;
			throw e;
		} finally {
			lock.unlock();
		}
	}

	private void openStreams() throws IOException {
		Socket s = connection.getSocket();
		s.setSoTimeout(POLL_MS);
		input = new ImapInput(s.getInputStream());
		input.setKeepWaiting(() -> !idleMode && state != State.DISCONNECTED
				&& System.currentTimeMillis() - Math.max(input.getLastReceived(), waitStart) < config.getReadTimeout());
		output = IoUtils.buffered(s.getOutputStream());
		reader = new ResponseReader(input);
		reader.setMaxMemoryLiteral(config.getMaxMemoryLiteral());
	}

	/** CAPABILITY: refresh the capabilities. */
	public Set<String> capability() throws IOException {
		return call(() -> {
			run(new Command("CAPABILITY", false));
			return capabilities;
		});
	}

	/**
	 * Log in: AUTHENTICATE PLAIN when offered (with SASL-IR), else LOGIN. Then
	 * IMAP4rev2 and UTF8=ACCEPT are enabled if the server offers them.
	 */
	public void login(String user, String password) throws IOException {
		call(() -> {
			requireState(State.NOT_AUTHENTICATED);
			Result r;
			if (hasCapability("AUTH=PLAIN") && hasCapability("SASL-IR")) {
				String ir = Sasl.encodePlain(user, password);
				Command c = new Command("AUTHENTICATE", false).raw("PLAIN");
				c.parts.add(ir);
				r = run(c);
			} else {
				if (hasCapability("LOGINDISABLED")) {
					throw new IOException("The server doesn't allow logging in on this connection (use TLS)");
				}
				r = run(new Command("LOGIN", false).string(user).secret(password));
			}
			state = State.AUTHENTICATED;
			if (r.tagged.getCodeName() == null || !"CAPABILITY".equals(r.tagged.getCodeName())) {
				run(new Command("CAPABILITY", false));
			}
			List<String> enable = new ArrayList<>();
			if (config.isEnableRev2() && hasCapability("IMAP4rev2")) {
				enable.add("IMAP4rev2");
			}
			if (config.isEnableUtf8() && hasCapability("UTF8=ACCEPT")) {
				enable.add("UTF8=ACCEPT");
			}
			if (!enable.isEmpty() && (hasCapability("ENABLE") || hasCapability("IMAP4rev2"))) {
				run(new Command("ENABLE", false).raw(String.join(" ", enable)));
			}
			return null;
		});
	}

	/** LOGOUT and close the connection. */
	public void logout() throws IOException {
		if (state == State.DISCONNECTED) {
			return;
		}
		lock.lock();
		try {
			stopIdleLocked();
			try {
				run(new Command("LOGOUT", false));
			} catch (IOException e) {
				// closing anyway
			}
			closeSocket();
			fireDisconnected(null);
		} finally {
			lock.unlock();
		}
	}

	/** Close the connection without LOGOUT. */
	@Override
	public void close() {
		try {
			if (state != State.DISCONNECTED) {
				logout();
			}
		} catch (IOException e) {
			// ignore
		} finally {
			closeSocket();
			if (worker != null) {
				worker.shutdown();
			}
			if (ownEvents != null) {
				ownEvents.shutdown();
			}
		}
	}

	private void closeSocket() {
		state = State.DISCONNECTED;
		selected = null;
		idleStopRequested = true;
		Client c = connection;
		if (c != null) {
			IoUtils.closeQuietly(c);
		}
	}

	// ------------------------------------------------------------------ mailboxes

	/** LIST reference pattern ("*" all, "%" one level). */
	public List<MailboxInfo> list(String reference, String pattern) throws IOException {
		return call(() -> parseList(run(new Command("LIST", isUtf8()).mailbox(reference).mailbox(pattern)), "LIST"));
	}

	/** Every mailbox. */
	public List<MailboxInfo> listAll() throws IOException {
		return list("", "*");
	}

	/** Subscribed mailboxes (LIST (SUBSCRIBED) or LSUB). */
	public List<MailboxInfo> listSubscribed() throws IOException {
		return call(() -> {
			if (hasCapability("LIST-EXTENDED") || isRev2()) {
				return parseList(run(new Command("LIST", isUtf8()).raw("(SUBSCRIBED)").mailbox("").mailbox("*")), "LIST");
			}
			return parseList(run(new Command("LSUB", isUtf8()).mailbox("").mailbox("*")), "LSUB");
		});
	}

	private List<MailboxInfo> parseList(Result r, String name) {
		List<MailboxInfo> ret = new ArrayList<>();
		for (Response x : r.untagged) {
			if (!name.equals(x.getName()) || x.tokens.size() < 4) {
				continue;
			}
			Set<String> attrs = new LinkedHashSet<>();
			for (Token a : x.tokens.get(1).items()) {
				attrs.add(a.text());
			}
			String delim = x.tokens.get(2).text();
			ret.add(new MailboxInfo(decodeName(x.tokens.get(3).text()), delim, attrs));
		}
		return ret;
	}

	private String decodeName(String raw) {
		return isUtf8() ? raw : ModifiedUtf7.decode(raw);
	}

	/** STATUS: counts without selecting (SIZE included when the server offers STATUS=SIZE). */
	public MailboxStatus status(String mailbox) throws IOException {
		return call(() -> {
			String items = "(MESSAGES UIDNEXT UIDVALIDITY UNSEEN" + (hasCapability("STATUS=SIZE") ? " SIZE" : "")
					+ (isRev2() ? " DELETED" : "") + ")";
			Result r = run(new Command("STATUS", isUtf8()).mailbox(mailbox).raw(items));
			MailboxStatus s = new MailboxStatus();
			s.name = mailbox;
			for (Response x : r.untagged) {
				if (!"STATUS".equals(x.getName()) || x.tokens.size() < 3) {
					continue;
				}
				List<Token> kv = x.tokens.get(2).items();
				for (int i = 0; i + 1 < kv.size(); i += 2) {
					long v = kv.get(i + 1).number();
					switch (kv.get(i).text().toUpperCase(Locale.ROOT)) {
					case "MESSAGES":
						s.messages = v;
						break;
					case "UIDNEXT":
						s.uidNext = v;
						break;
					case "UIDVALIDITY":
						s.uidValidity = v;
						break;
					case "UNSEEN":
						s.unseen = v;
						break;
					case "DELETED":
						s.deleted = v;
						break;
					case "SIZE":
						s.size = v;
						break;
					default:
					}
				}
			}
			return s;
		});
	}

	public void create(String mailbox) throws IOException {
		call(() -> run(new Command("CREATE", isUtf8()).mailbox(mailbox)));
	}

	public void delete(String mailbox) throws IOException {
		call(() -> run(new Command("DELETE", isUtf8()).mailbox(mailbox)));
	}

	public void rename(String from, String to) throws IOException {
		call(() -> run(new Command("RENAME", isUtf8()).mailbox(from).mailbox(to)));
	}

	public void subscribe(String mailbox) throws IOException {
		call(() -> run(new Command("SUBSCRIBE", isUtf8()).mailbox(mailbox)));
	}

	public void unsubscribe(String mailbox) throws IOException {
		call(() -> run(new Command("UNSUBSCRIBE", isUtf8()).mailbox(mailbox)));
	}

	/** SELECT a mailbox for reading and changing; the UIDs of its messages are loaded. */
	public SelectedMailbox select(String mailbox) throws IOException {
		return open("SELECT", mailbox);
	}

	/** EXAMINE: select read-only (fetching doesn't set \Seen). */
	public SelectedMailbox examine(String mailbox) throws IOException {
		return open("EXAMINE", mailbox);
	}

	private SelectedMailbox open(String verb, String mailbox) throws IOException {
		return call(() -> {
			requireAuthenticated();
			SelectedMailbox s = new SelectedMailbox(mailbox);
			selected = s;
			try {
				Result r = run(new Command(verb, isUtf8()).mailbox(mailbox));
				s.readOnly = "READ-ONLY".equals(r.tagged.getCodeName()) || verb.equals("EXAMINE");
				state = State.SELECTED;
				if (s.exists > 0) {
					s.setUids(searchUids("ALL"));
				}
				return s;
			} catch (IOException | RuntimeException e) {
				selected = null;
				state = State.AUTHENTICATED;
				throw e;
			}
		});
	}

	/** CLOSE: leave the mailbox, removing messages marked \Deleted. */
	public void closeMailbox() throws IOException {
		call(() -> {
			run(new Command("CLOSE", false));
			selected = null;
			state = State.AUTHENTICATED;
			return null;
		});
	}

	/** UNSELECT (or CLOSE on servers without it, which removes \Deleted messages). */
	public void unselect() throws IOException {
		call(() -> {
			run(new Command(hasCapability("UNSELECT") || isRev2() ? "UNSELECT" : "CLOSE", false));
			selected = null;
			state = State.AUTHENTICATED;
			return null;
		});
	}

	// ------------------------------------------------------------------ messages

	private static final String SUMMARY_ITEMS = "(UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE)";

	/** Summaries of messages by UID set, e.g. "1:*" or "100,105:110"; in UID order. */
	public List<MessageSummary> fetchSummaries(String uidSet) throws IOException {
		return call(() -> {
			requireSelected();
			return summaries(run(new Command("UID FETCH", false).raw(uidSet).raw(SUMMARY_ITEMS)));
		});
	}

	/** Summaries by message sequence numbers, e.g. the newest 50: {@code "*:" + (exists - 49)}. */
	public List<MessageSummary> fetchSummariesBySequence(String sequenceSet) throws IOException {
		return call(() -> {
			requireSelected();
			return summaries(run(new Command("FETCH", false).raw(sequenceSet).raw(SUMMARY_ITEMS)));
		});
	}

	private List<MessageSummary> summaries(Result r) {
		List<MessageSummary> ret = new ArrayList<>();
		for (Response x : r.untagged) {
			if ("FETCH".equals(x.getName())) {
				MessageSummary s = parseFetch(x);
				if (s.uid >= 0) {
					ret.add(s);
				}
			}
		}
		ret.sort((a, b) -> Long.compare(a.uid, b.uid));
		return ret;
	}

	/** Parse "* n FETCH (...)" into a summary (fields not in the response stay unset). */
	static MessageSummary parseFetch(Response x) {
		MessageSummary s = new MessageSummary();
		s.msn = x.getNumber();
		List<Token> items = x.tokens.size() > 2 ? x.tokens.get(2).items() : Collections.emptyList();
		for (int i = 0; i + 1 < items.size(); i += 2) {
			String name = items.get(i).text().toUpperCase(Locale.ROOT);
			Token v = items.get(i + 1);
			switch (name) {
			case "UID":
				s.uid = v.number();
				break;
			case "FLAGS": {
				List<String> f = new ArrayList<>();
				for (Token t : v.items()) {
					f.add(t.text());
				}
				s.flags = SelectedMailbox.set(f);
				break;
			}
			case "INTERNALDATE":
				try {
					s.internalDate = ZonedDateTime.parse(v.text().trim(), INTERNALDATE).toInstant();
				} catch (RuntimeException e) {
					s.internalDate = null;
				}
				break;
			case "RFC822.SIZE":
				s.size = v.number();
				break;
			case "ENVELOPE":
				s.envelope = Envelope.parse(v);
				break;
			case "BODYSTRUCTURE":
			case "BODY":
				if (v.isList()) {
					s.structure = BodyPart.parse(v);
				}
				break;
			default:
			}
		}
		return s;
	}

	/** Fetch only the flags of messages (for refreshing a list). UID to flags. */
	public Map<Long, Set<String>> fetchFlags(String uidSet) throws IOException {
		return call(() -> {
			requireSelected();
			Map<Long, Set<String>> ret = new LinkedHashMap<>();
			for (MessageSummary s : summaries(run(new Command("UID FETCH", false).raw(uidSet).raw("(UID FLAGS)")))) {
				ret.put(s.uid, s.flags);
			}
			return ret;
		});
	}

	/**
	 * Stream a whole message (as stored, RFC 5322) to {@code out}.
	 *
	 * @param markSeen set \Seen (BODY[] instead of BODY.PEEK[])
	 * @return the size in bytes
	 */
	public long fetchMessage(long uid, OutputStream out, boolean markSeen, ProgressListener progress) throws IOException {
		return fetchSection(uid, (markSeen ? "BODY[" : "BODY.PEEK[") + "]", out, progress);
	}

	/**
	 * A whole message, parsed. Large messages are kept in a temp file by
	 * {@link Message} (close it to delete the file); nothing large is held in memory.
	 */
	public Message fetchMessage(long uid) throws IOException {
		FileSource tmp = FileSourceFactory.getDefaultFactory().createTempFile("imapfetch", ".eml");
		try {
			try (OutputStream out = IoUtils.buffered(tmp.getOutputStream())) {
				fetchMessage(uid, out, false, null);
			}
			try (InputStream in = IoUtils.buffered(tmp.getInputStream())) {
				return Message.read(in);
			}
		} finally {
			tmp.delete();
		}
	}

	/** The header block of a message, parsed (BODY.PEEK[HEADER]). */
	public Message fetchHeaders(long uid) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		fetchSection(uid, "BODY.PEEK[HEADER]", out, null);
		return Message.parse(out.toByteArray());
	}

	/**
	 * Stream one MIME part, decoded (base64 and quoted-printable removed), e.g. an
	 * attachment to a file. Uses BINARY (RFC 3516) when the server has it.
	 */
	public long fetchPart(long uid, BodyPart part, OutputStream out, ProgressListener progress) throws IOException {
		String n = part.getPartNumber();
		String enc = part.getEncoding();
		boolean encoded = enc.equals("BASE64") || enc.equals("QUOTED-PRINTABLE");
		if (!encoded || n.isEmpty() || part.isMultipart()) {
			return fetchSection(uid, "BODY.PEEK[" + n + "]", out, progress);
		}
		if (hasCapability("BINARY") || isRev2()) {
			try {
				return fetchSection(uid, "BINARY.PEEK[" + n + "]", out, progress);
			} catch (ImapException e) {
				if (!e.hasCode("UNKNOWN-CTE")) {
					throw e;
				}
			}
		}
		// decode here: fetch the encoded part into a temp file first
		FileSource tmp = FileSourceFactory.getDefaultFactory().createTempFile("imappart", ".tmp");
		try {
			try (OutputStream o = IoUtils.buffered(tmp.getOutputStream())) {
				fetchSection(uid, "BODY.PEEK[" + n + "]", o, progress);
			}
			long count = 0;
			try (InputStream raw = IoUtils.buffered(tmp.getInputStream());
					InputStream in = enc.equals("BASE64") ? Base64.getMimeDecoder().wrap(raw) : QuotedPrintable.decoder(raw)) {
				byte[] buf = new byte[64 * 1024];
				int r;
				while ((r = in.read(buf)) > 0) {
					out.write(buf, 0, r);
					count += r;
				}
			}
			return count;
		} finally {
			tmp.delete();
		}
	}

	/** A text part as a string (decoded with its charset). */
	public String fetchText(long uid, BodyPart part) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		fetchPart(uid, part, out, null);
		return new String(out.toByteArray(), part.charset());
	}

	/** Stream one section's literal (e.g. "BODY.PEEK[2.MIME]") to {@code out}; returns its size. */
	public long fetchSection(long uid, String item, OutputStream out, ProgressListener progress) throws IOException {
		return call(() -> {
			requireSelected();
			long[] got = {-1};
			reader.setSink(size -> {
				got[0] = size;
				return new ProgressOutputStream(out, size, progress);
			});
			try {
				run(new Command("UID FETCH", false).raw(Long.toString(uid)).raw("(UID " + item + ")"));
			} finally {
				reader.setSink(null);
			}
			if (got[0] < 0) {
				throw new ImapException("NO", "NONEXISTENT", "No message with UID " + uid, "UID FETCH");
			}
			return got[0];
		});
	}

	/** Counts bytes for a {@link ProgressListener}; never closes the caller's stream. */
	private static final class ProgressOutputStream extends FilterOutputStream {
		private final long total;
		private final ProgressListener progress;
		private long done;
		private long lastReport;

		ProgressOutputStream(OutputStream out, long total, ProgressListener progress) {
			super(out);
			this.total = total;
			this.progress = progress;
			if (progress != null) {
				progress.progress(0, total);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			done += len;
			if (progress != null && (done == total || done - lastReport >= 64 * 1024)) {
				lastReport = done;
				progress.progress(done, total);
			}
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] {(byte) b}, 0, 1);
		}

		@Override
		public void close() throws IOException {
			flush();
		}
	}

	// ------------------------------------------------------------------ search

	/** UID SEARCH with raw criteria, e.g. "UNSEEN SINCE 1-Oct-2026". UIDs in ascending order. */
	public List<Long> search(String criteria) throws IOException {
		return call(() -> {
			requireSelected();
			return searchUids(criteria);
		});
	}

	/** UID SEARCH with built criteria (strings are quoted or sent as literals as needed). */
	public List<Long> search(SearchCriteria criteria) throws IOException {
		return call(() -> {
			requireSelected();
			Command c = new Command("UID SEARCH", isUtf8());
			if (criteria.needsUtf8() && !isUtf8()) {
				c.raw("CHARSET UTF-8");
			}
			criteria.appendTo(c);
			return parseSearch(run(c));
		});
	}

	private List<Long> searchUids(String criteria) throws IOException {
		return parseSearch(run(new Command("UID SEARCH", false).raw(criteria)));
	}

	static List<Long> parseSearch(Result r) {
		List<Long> ret = new ArrayList<>();
		for (Response x : r.untagged) {
			String name = x.getName();
			if ("SEARCH".equals(name)) {
				for (int i = 1; i < x.tokens.size(); i++) {
					if (x.tokens.get(i).isAtom() && Response.isNumber(x.tokens.get(i).text())) {
						ret.add(x.tokens.get(i).number());
					}
				}
			} else if ("ESEARCH".equals(name)) {
				List<Token> t = x.tokens;
				for (int i = 1; i + 1 < t.size(); i++) {
					if (t.get(i).isAtom() && "ALL".equalsIgnoreCase(t.get(i).text())) {
						ret.addAll(expand(t.get(i + 1).text()));
					}
				}
			}
		}
		Collections.sort(ret);
		return ret;
	}

	/** "1:3,7" to [1, 2, 3, 7]. */
	static List<Long> expand(String set) {
		List<Long> ret = new ArrayList<>();
		for (String p : set.split(",")) {
			int c = p.indexOf(':');
			if (c < 0) {
				ret.add(Long.parseLong(p));
			} else {
				long a = Long.parseLong(p.substring(0, c));
				long b = Long.parseLong(p.substring(c + 1));
				for (long v = Math.min(a, b); v <= Math.max(a, b); v++) {
					ret.add(v);
				}
			}
		}
		return ret;
	}

	/** "1:3,7" from UIDs (ascending runs joined). */
	public static String uidSet(Collection<Long> uids) {
		List<Long> sorted = new ArrayList<>(new java.util.TreeSet<>(uids));
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < sorted.size()) {
			int j = i;
			while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) {
				j++;
			}
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(sorted.get(i));
			if (j > i) {
				sb.append(':').append(sorted.get(j));
			}
			i = j + 1;
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ changes

	public void addFlags(String uidSet, String... flags) throws IOException {
		store(uidSet, "+FLAGS.SILENT", flags);
	}

	public void removeFlags(String uidSet, String... flags) throws IOException {
		store(uidSet, "-FLAGS.SILENT", flags);
	}

	public void setFlags(String uidSet, String... flags) throws IOException {
		store(uidSet, "FLAGS.SILENT", flags);
	}

	private void store(String uidSet, String item, String... flags) throws IOException {
		call(() -> {
			requireSelected();
			run(new Command("UID STORE", false).raw(uidSet).raw(item).raw("(" + String.join(" ", flags) + ")"));
			return null;
		});
	}

	public CopyResult copy(String uidSet, String mailbox) throws IOException {
		return call(() -> {
			requireSelected();
			return copyResult(run(new Command("UID COPY", isUtf8()).raw(uidSet).mailbox(mailbox)));
		});
	}

	/**
	 * Move messages (MOVE, RFC 6851). Without MOVE: COPY, then mark \Deleted and
	 * UID EXPUNGE (needs UIDPLUS, so other deleted messages aren't removed).
	 */
	public CopyResult move(String uidSet, String mailbox) throws IOException {
		return call(() -> {
			requireSelected();
			if (hasCapability("MOVE") || isRev2()) {
				return copyResult(run(new Command("UID MOVE", isUtf8()).raw(uidSet).mailbox(mailbox)));
			}
			if (!hasCapability("UIDPLUS")) {
				throw new IOException("The server supports neither MOVE nor UIDPLUS");
			}
			CopyResult r = copyResult(run(new Command("UID COPY", isUtf8()).raw(uidSet).mailbox(mailbox)));
			run(new Command("UID STORE", false).raw(uidSet).raw("+FLAGS.SILENT").raw("(\\Deleted)"));
			run(new Command("UID EXPUNGE", false).raw(uidSet));
			return r;
		});
	}

	private static CopyResult copyResult(Result r) {
		for (Response x : r.codes) {
			if ("COPYUID".equals(x.getCodeName())) {
				String[] p = x.getCodeArgs().split(" ");
				if (p.length == 3) {
					List<Long> from = expand(p[1]);
					List<Long> to = expand(p[2]);
					Map<Long, Long> m = new LinkedHashMap<>();
					for (int i = 0; i < from.size() && i < to.size(); i++) {
						m.put(from.get(i), to.get(i));
					}
					return new CopyResult(Long.parseLong(p[0]), m);
				}
			}
		}
		return new CopyResult(-1, Collections.emptyMap());
	}

	/** Mark messages \Deleted and remove them (UID EXPUNGE when available, else EXPUNGE). */
	public void deleteMessages(String uidSet) throws IOException {
		call(() -> {
			requireSelected();
			run(new Command("UID STORE", false).raw(uidSet).raw("+FLAGS.SILENT").raw("(\\Deleted)"));
			if (hasCapability("UIDPLUS") || isRev2()) {
				run(new Command("UID EXPUNGE", false).raw(uidSet));
			} else {
				run(new Command("EXPUNGE", false));
			}
			return null;
		});
	}

	/** EXPUNGE: remove every message marked \Deleted. */
	public void expunge() throws IOException {
		call(() -> run(new Command("EXPUNGE", false)));
	}

	/**
	 * APPEND a message (e.g. a sent message to "Sent", a draft to "Drafts").
	 *
	 * @return the new UID (APPENDUID), or -1 if the server doesn't say
	 */
	public long append(String mailbox, InputStream content, long size, Collection<String> flags, Instant date,
			ProgressListener progress) throws IOException {
		return call(() -> {
			requireAuthenticated();
			Command c = new Command("APPEND", isUtf8()).mailbox(mailbox);
			if (flags != null && !flags.isEmpty()) {
				c.raw("(" + String.join(" ", flags) + ")");
			}
			if (date != null) {
				c.raw("\"" + APPEND_DATE.format(date.atZone(ZoneOffset.UTC)) + "\"");
			}
			c.literal(content, size, false, progress);
			Result r = run(c);
			if ("APPENDUID".equals(r.tagged.getCodeName())) {
				String[] p = r.tagged.getCodeArgs().split(" ");
				if (p.length == 2) {
					return Long.parseLong(p[1]);
				}
			}
			return -1L;
		});
	}

	/** APPEND a {@link Message} (written to a temp file first, so it can be any size). */
	public long append(String mailbox, Message message, Collection<String> flags) throws IOException {
		FileSource tmp = FileSourceFactory.getDefaultFactory().createTempFile("imapappend", ".eml");
		try {
			try (OutputStream out = IoUtils.buffered(tmp.getOutputStream())) {
				message.writeTo(out);
			}
			try (InputStream in = IoUtils.buffered(tmp.getInputStream())) {
				return append(mailbox, in, tmp.length(), flags, null, null);
			}
		} finally {
			tmp.delete();
		}
	}

	/** NOOP: lets the server report changes (new mail, expunges, flags). */
	public void noop() throws IOException {
		call(() -> run(new Command("NOOP", false)));
	}

	/** Send any command (for extensions this class doesn't cover); returns its responses. */
	public List<Response> execute(String command) throws IOException {
		return call(() -> {
			int sp = command.indexOf(' ');
			Command c = new Command(sp < 0 ? command : command.substring(0, sp), isUtf8());
			if (sp > 0) {
				c.raw(command.substring(sp + 1));
			}
			Result r = run(c);
			List<Response> ret = new ArrayList<>(r.untagged);
			ret.add(r.tagged);
			return ret;
		});
	}

	// ------------------------------------------------------------------ IDLE

	/**
	 * Wait for changes in the background (IDLE, RFC 9051 section 6.3.13): events
	 * arrive at the listeners as they happen. Any other command stops IDLE first.
	 * On servers without IDLE the client polls with NOOP instead.
	 */
	public void startIdle() throws IOException {
		lock.lock();
		try {
			requireAuthenticated();
			wantIdle = true;
			if (idleMode || poller != null) {
				return;
			}
			if (hasCapability("IDLE") || isRev2()) {
				beginIdleLocked();
			} else {
				startPoller();
			}
		} finally {
			lock.unlock();
		}
	}

	/** Stop IDLE (or polling). */
	public void stopIdle() {
		lock.lock();
		try {
			wantIdle = false;
			stopIdleLocked();
		} finally {
			lock.unlock();
		}
	}

	/** Keep IDLE running between commands: after each command IDLE starts again. */
	public void setAutoIdle(boolean autoIdle) {
		this.autoIdle = autoIdle;
	}

	public boolean isIdling() {
		return idleMode || poller != null;
	}

	private void beginIdleLocked() throws IOException {
		String tag = nextTag();
		waitStart = System.currentTimeMillis();
		send(tag, new Command("IDLE", false), null);
		while (true) {
			Response r = readResponse();
			if (r == null) {
				throw connectionLost(new java.io.EOFException("Connection closed"));
			}
			if (r.isContinuation()) {
				break;
			}
			if (r.isUntagged()) {
				handleUntagged(r, "IDLE");
			} else if (tag.equals(r.tag)) {
				throw new ImapException(r.status, r.code, r.text, "IDLE");
			}
		}
		idleTag = tag;
		doneSent.set(false);
		idleStopRequested = false;
		idleStarted = System.currentTimeMillis();
		idleMode = true;
		idleThread = new Thread(this::idleLoop, "ImapClient-idle");
		idleThread.setDaemon(true);
		idleThread.start();
	}

	private void idleLoop() {
		try {
			while (true) {
				Response r;
				try {
					r = reader.read();
				} catch (SocketTimeoutException e) {
					long restart = config.getIdleRestartMinutes() * 60_000L;
					if (idleStopRequested || System.currentTimeMillis() - idleStarted > restart) {
						sendDone();
					}
					if (state == State.DISCONNECTED) {
						return;
					}
					continue;
				}
				if (r == null) {
					throw new java.io.EOFException("Connection closed");
				}
				traceResponse(r);
				if (r.isUntagged()) {
					handleUntagged(r, "IDLE");
				} else if (r.isContinuation()) {
					continue;
				} else if (r.tag.equals(idleTag)) {
					if (idleStopRequested || state == State.DISCONNECTED) {
						return;
					}
					// restart (RFC 9051: re-issue IDLE at least every 29 minutes)
					String tag = nextTag();
					idleTag = tag;
					doneSent.set(false);
					idleStarted = System.currentTimeMillis();
					send(tag, new Command("IDLE", false), null);
				}
			}
		} catch (IOException e) {
			if (!idleStopRequested) {
				connectionLost(e);
			}
		} finally {
			idleMode = false;
		}
	}

	private void sendDone() {
		if (doneSent.compareAndSet(false, true)) {
			try {
				synchronized (writeLock) {
					output.write("DONE\r\n".getBytes(StandardCharsets.US_ASCII));
					output.flush();
				}
				traceLine("C: DONE");
			} catch (IOException e) {
				// the idle loop will see the broken connection
			}
		}
	}

	/** Stop IDLE or polling; the lock is held. */
	private void stopIdleLocked() {
		Thread p = poller;
		if (p != null) {
			poller = null;
			p.interrupt();
			try {
				p.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Thread t = idleThread;
		if (t == null) {
			return;
		}
		idleStopRequested = true;
		sendDone();
		try {
			t.join(Math.max(5000, config.getReadTimeout()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		idleThread = null;
		idleMode = false;
	}

	private void startPoller() {
		Thread p = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted() && state != State.DISCONNECTED) {
				try {
					Thread.sleep(config.getPollSeconds() * 1000L);
				} catch (InterruptedException e) {
					return;
				}
				if (!lock.tryLock()) {
					continue; // a command is running and will report changes
				}
				try {
					if (poller == Thread.currentThread()) {
						run(new Command("NOOP", false));
					}
				} catch (IOException e) {
					return;
				} finally {
					lock.unlock();
				}
			}
		}, "ImapClient-poll");
		p.setDaemon(true);
		poller = p;
		p.start();
	}

	// ------------------------------------------------------------------ command engine

	@FunctionalInterface
	private interface Op<T> {
		T run() throws IOException;
	}

	/** Run an operation with the connection to itself: IDLE is stopped first and (with auto idle) resumed after. */
	private <T> T call(Op<T> op) throws IOException {
		lock.lock();
		try {
			if (state == State.DISCONNECTED) {
				throw new IOException("Not connected");
			}
			boolean resume = wantIdle && (idleMode || poller != null);
			stopIdleLocked();
			try {
				syncUids();
				T ret = op.run();
				syncUids();
				return ret;
			} finally {
				if (state != State.DISCONNECTED && (resume || (autoIdle && wantIdle))) {
					try {
						if (hasCapability("IDLE") || isRev2()) {
							beginIdleLocked();
						} else {
							startPoller();
						}
					} catch (IOException e) {
						// IDLE couldn't restart: the next command will report the problem
					}
				}
			}
		} finally {
			lock.unlock();
		}
	}

	/**
	 * New messages announced by EXISTS (e.g. during IDLE) have no UID yet: learn
	 * them, so {@link SelectedMailbox#getUids()} is complete after every call.
	 */
	private void syncUids() throws IOException {
		SelectedMailbox s = selected;
		if (s == null || state != State.SELECTED) {
			return;
		}
		long first = s.firstUnknown();
		if (first > 0) {
			run(new Command("FETCH", false).raw(first + ":*").raw("(UID)"));
		}
	}

	private String nextTag() {
		return String.format("A%04d", tags.incrementAndGet());
	}

	/** Send a command and read responses up to its tagged completion. The lock is held. */
	private Result run(Command cmd) throws IOException {
		if (state == State.DISCONNECTED) {
			throw new IOException("Not connected");
		}
		String tag = nextTag();
		Result result = new Result();
		currentCommand = cmd.name;
		waitStart = System.currentTimeMillis();
		send(tag, cmd, result);
		while (true) {
			Response r = readResponse();
			if (r == null) {
				throw connectionLost(new java.io.EOFException("Connection closed by the server"));
			}
			if (r.isContinuation()) {
				continue;
			}
			if (r.isUntagged()) {
				handleUntagged(r, cmd.name);
				if (r.code != null) {
					result.codes.add(r);
				}
				result.untagged.add(r);
				continue;
			}
			if (tag.equals(r.tag)) {
				result.tagged = r;
				handleCodes(r, cmd.name);
				if (r.code != null) {
					result.codes.add(r);
				}
				if (!r.isOk()) {
					throw new ImapException(r.status, r.code, r.text, cmd.name);
				}
				return result;
			}
		}
	}

	/** Write a command; synchronizing literals wait for the server's "+". */
	private void send(String tag, Command cmd, Result result) throws IOException {
		traceLine("C: " + tag + " " + cmd);
		try {
			synchronized (writeLock) {
				output.write((tag + " " + cmd.name).getBytes(StandardCharsets.UTF_8));
			}
			for (Object part : cmd.parts) {
				if (part instanceof String) {
					synchronized (writeLock) {
						output.write(' ');
						output.write(((String) part).getBytes(StandardCharsets.UTF_8));
					}
					continue;
				}
				Command.Literal lit = (Command.Literal) part;
				boolean nonSync = hasCapability("LITERAL+") || ((hasCapability("LITERAL-") || isRev2()) && lit.size <= 4096);
				synchronized (writeLock) {
					output.write((" " + (lit.binary ? "~" : "") + "{" + lit.size + (nonSync ? "+" : "") + "}\r\n")
							.getBytes(StandardCharsets.US_ASCII));
					output.flush();
				}
				if (!nonSync) {
					while (true) {
						Response r = readResponse();
						if (r == null) {
							throw connectionLost(new java.io.EOFException("Connection closed"));
						}
						if (r.isContinuation()) {
							break;
						}
						if (r.isUntagged()) {
							handleUntagged(r, cmd.name);
							if (result != null) {
								result.untagged.add(r);
							}
						} else if (tag.equals(r.tag)) {
							throw new ImapException(r.status, r.code, r.text, cmd.name);
						}
					}
				}
				synchronized (writeLock) {
					OutputStream o = new ProgressOutputStream(output, lit.size, lit.progress);
					byte[] buf = new byte[64 * 1024];
					long left = lit.size;
					while (left > 0) {
						int n = lit.data.read(buf, 0, (int) Math.min(buf.length, left));
						if (n < 0) {
							throw new IOException("The content is shorter than its size");
						}
						o.write(buf, 0, n);
						left -= n;
					}
				}
			}
			synchronized (writeLock) {
				output.write('\r');
				output.write('\n');
				output.flush();
			}
		} catch (ImapException e) {
			throw e;
		} catch (IOException e) {
			throw connectionLost(e);
		}
	}

	private Response readResponse() throws IOException {
		try {
			Response r = reader.read();
			if (r != null) {
				traceResponse(r);
			}
			return r;
		} catch (SocketTimeoutException e) {
			throw connectionLost(new IOException("No answer from the server in " + config.getReadTimeout() / 1000 + "s", e));
		} catch (ImapProtocolException e) {
			throw connectionLost(new IOException(e.getMessage(), e));
		} catch (IOException e) {
			throw connectionLost(e);
		}
	}

	private IOException connectionLost(IOException e) {
		if (state != State.DISCONNECTED) {
			closeSocket();
			fireDisconnected(e);
		}
		return e;
	}

	private void fireDisconnected(Exception cause) {
		if (disconnectedFired.compareAndSet(false, true)) {
			for (ImapListener l : listeners) {
				fire(() -> l.disconnected(cause));
			}
		}
	}

	private void fire(Runnable r) {
		try {
			events.execute(r);
		} catch (RuntimeException e) {
			// the executor is shut down
		}
	}

	// ------------------------------------------------------------------ responses

	/** Untagged responses update the state and become events. */
	private void handleUntagged(Response r, String command) {
		if (r.isStatus()) {
			handleCodes(r, command);
			if ("BYE".equals(r.status)) {
				String text = r.text;
				for (ImapListener l : listeners) {
					fire(() -> l.bye(text));
				}
			}
			return;
		}
		String name = r.getName();
		if (name == null) {
			return;
		}
		SelectedMailbox s = selected;
		switch (name) {
		case "CAPABILITY":
			capabilities = caps(r.tokens.subList(1, r.tokens.size()));
			break;
		case "ENABLED":
			for (int i = 1; i < r.tokens.size(); i++) {
				enabled.add(r.tokens.get(i).text().toUpperCase(Locale.ROOT));
			}
			break;
		case "FLAGS":
			if (s != null && r.tokens.size() > 1) {
				s.flags = SelectedMailbox.set(texts(r.tokens.get(1)));
			}
			break;
		case "EXISTS":
			if (s != null) {
				long n = r.getNumber();
				s.setExists(n);
				if (command.equals("SELECT") || command.equals("EXAMINE")) {
					break; // the count is in the result of select()
				}
				for (ImapListener l : listeners) {
					fire(() -> l.exists(s.getName(), n));
				}
			}
			break;
		case "EXPUNGE":
			if (s != null) {
				long msn = r.getNumber();
				long uid = s.expunge(msn);
				for (ImapListener l : listeners) {
					fire(() -> l.expunged(s.getName(), msn, uid));
				}
			}
			break;
		case "FETCH":
			if (s != null) {
				MessageSummary m = parseFetch(r);
				if (m.uid >= 0) {
					s.setUid(m.msn, m.uid);
				}
				boolean hasFlags = false;
				List<Token> items = r.tokens.size() > 2 ? r.tokens.get(2).items() : Collections.emptyList();
				for (int i = 0; i < items.size(); i += 2) {
					hasFlags |= "FLAGS".equalsIgnoreCase(items.get(i).text());
				}
				if (hasFlags && !command.endsWith("FETCH")) {
					long uid = m.uid >= 0 ? m.uid : s.getUid(m.msn);
					Set<String> flags = m.flags;
					for (ImapListener l : listeners) {
						fire(() -> l.flagsChanged(s.getName(), m.msn, uid, flags));
					}
				}
			}
			break;
		default:
		}
	}

	private void handleCodes(Response r, String command) {
		String code = r.getCodeName();
		if (code == null) {
			return;
		}
		SelectedMailbox s = selected;
		String args = r.getCodeArgs();
		switch (code) {
		case "CAPABILITY":
			List<Token> t = new ArrayList<>();
			for (String c : args.split(" ")) {
				t.add(Token.atom(c));
			}
			capabilities = caps(t);
			break;
		case "UIDVALIDITY":
			if (s != null) {
				s.uidValidity = Long.parseLong(args.trim());
			}
			break;
		case "UIDNEXT":
			if (s != null) {
				s.uidNext = Long.parseLong(args.trim());
			}
			break;
		case "PERMANENTFLAGS":
			if (s != null) {
				String inner = args.replace("(", "").replace(")", "").trim();
				s.permanentFlags = SelectedMailbox.set(inner.isEmpty() ? Collections.emptyList()
						: java.util.Arrays.asList(inner.split(" +")));
			}
			break;
		case "READ-ONLY":
			if (s != null) {
				s.readOnly = true;
			}
			break;
		case "READ-WRITE":
			if (s != null) {
				s.readOnly = false;
			}
			break;
		case "ALERT":
			String text = r.text;
			for (ImapListener l : listeners) {
				fire(() -> l.alert(text));
			}
			break;
		default:
		}
	}

	private static Set<String> caps(List<Token> tokens) {
		Set<String> ret = new LinkedHashSet<>();
		for (Token t : tokens) {
			if (t.isAtom()) {
				ret.add(t.text().toUpperCase(Locale.ROOT));
			}
		}
		return Collections.unmodifiableSet(ret);
	}

	private static List<String> texts(Token list) {
		List<String> ret = new ArrayList<>();
		for (Token t : list.items()) {
			ret.add(t.text());
		}
		return ret;
	}

	private void requireState(State s) {
		if (state != s) {
			throw new IllegalStateException("Not valid in the " + state + " state");
		}
	}

	private void requireAuthenticated() {
		if (state != State.AUTHENTICATED && state != State.SELECTED) {
			throw new IllegalStateException("Log in first");
		}
	}

	private void requireSelected() {
		if (state != State.SELECTED || selected == null) {
			throw new IllegalStateException("Select a mailbox first");
		}
	}

	// ------------------------------------------------------------------ trace

	private void traceLine(String s) {
		Consumer<String> t = trace;
		if (t != null) {
			t.accept(s);
		}
	}

	private void traceResponse(Response r) {
		Consumer<String> t = trace;
		if (t != null) {
			String s = r.toString();
			t.accept("S: " + (s.length() > 1000 ? s.substring(0, 1000) + "..." : s));
		}
	}

	@Override
	public String toString() {
		return "ImapClient[" + config.getHost() + ":" + config.getPort() + " " + state + "]";
	}

	/** For tests and tools: the command running now. */
	String getCurrentCommand() {
		return currentCommand;
	}
}
