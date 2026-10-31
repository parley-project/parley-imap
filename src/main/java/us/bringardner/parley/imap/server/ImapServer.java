package us.bringardner.parley.imap.server;

import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.mail.server.AbstractMailServer;
import java.io.IOException;
import java.util.ArrayDeque;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.IProcessorFactory;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.MailboxRegistry;

/**
 * An IMAP server: IMAP4rev2 (RFC 9051), also speaking IMAP4rev1 (RFC 3501) to
 * clients that don't ENABLE IMAP4rev2. Built like FtpServer in BjlNetFtp and
 * Pop3Server: an {@link ImapRequestProcessor} runs each session, with one
 * command class per IMAP command from {@link ImapCommandFactory}.
 * <p>
 * Mail is shared with Pop3Server: the maildrop root is the same, a user's INBOX
 * is their POP3 maildrop, and the user's other mailboxes are kept under it (see
 * {@link MailStore}). Users come from the access control list
 * ({@code parley.mail.imap.authenticationProvider}): READ is needed to log in, WRITE
 * to change anything.
 */
public class ImapServer extends AbstractMailServer implements IMAP {


	public static final String IMAP_NAME = "JImap";
	/** The prefix of the properties of an IMAP server (port 143, STARTTLS). */
	public static final String IMAP_PREFIX = "parley.mail.imap";
	/** The prefix of the properties of an IMAP server with implicit TLS (port 993). */
	public static final String IMAPS_PREFIX = "parley.mail.imaps";
	/** A settings file for the server's configuration (see parley-net's ServerLauncher). */
	public static final String CONFIG_PROP = IMAP_PREFIX + ".configFile";
	/** The maildrop root, shared with POP3 and SMTP so the servers share mail. */
	public static final String ROOT_PROP = MAIL_ROOT_PROP;
	public static final String FILE_SOURCE_PROP = MAIL_FILE_SOURCE_PROP;
	public static final String DEFAULT_ROOT_WINDOWS = "C:/pop3";
	public static final String DEFAULT_ROOT = "/pop3";

	/** RFC 9051 section 5.4: at least 30 minutes. */
	public static final int DEFAULT_AUTOLOGOUT = 30 * 60 * 1000;
	/** A server's idle time setting, after its prefix ({@value #IMAP_PREFIX} or {@value #IMAPS_PREFIX}) */
	public static final String AUTOLOGOUT = "autologout";
	/** The IMAP (port 143) server's {@link #AUTOLOGOUT} */
	public static final String AUTOLOGOUT_PROP = IMAP_PREFIX + "." + AUTOLOGOUT;
	public static final int DEFAULT_LOGIN_FAILURE_DELAY = 1000;
	/** Refuse LOGIN and AUTHENTICATE until STARTTLS (LOGINDISABLED); after the server's prefix. */
	public static final String REQUIRE_TLS = "requireTls";
	/** The IMAP (port 143) server's {@link #REQUIRE_TLS} */
	public static final String REQUIRE_TLS_PROP = IMAP_PREFIX + "." + REQUIRE_TLS;
	/** The largest message APPEND accepts; after the server's prefix. */
	public static final String APPEND_LIMIT = "appendLimit";
	/** The IMAP (port 143) server's {@link #APPEND_LIMIT} */
	public static final String APPEND_LIMIT_PROP = IMAP_PREFIX + "." + APPEND_LIMIT;
	public static final long DEFAULT_APPEND_LIMIT = 100L * 1024 * 1024;
	/** Create Sent, Drafts, Trash, Junk and Archive for a new user; after the server's prefix. */
	public static final String DEFAULT_MAILBOXES = "defaultMailboxes";
	/** The IMAP (port 143) server's {@link #DEFAULT_MAILBOXES} */
	public static final String DEFAULT_MAILBOXES_PROP = IMAP_PREFIX + "." + DEFAULT_MAILBOXES;

	// (read from this server's own settings, under its own prefix)
	private volatile long appendLimit = longSetting(APPEND_LIMIT, DEFAULT_APPEND_LIMIT);
	private volatile boolean createDefaultMailboxes = getBooleanProperty(propertyName(DEFAULT_MAILBOXES), true);

	private final MailboxRegistry registry = MailboxRegistry.get();

	public ImapServer(int port, String name, boolean secure) {
		super(port, name, secure ? IMAPS_PREFIX : IMAP_PREFIX, secure);
		initMe();
		finishInit();
	}

	@Override
	protected String getRootProperty() {
		return ROOT_PROP;
	}

	/** A whole-number setting of this server (after its prefix), or the default when it isn't set or isn't a number */
	private long longSetting(String name, long defaultValue) {
		String v = getProperty(propertyName(name));
		if (v == null || v.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(v.trim());
		} catch (NumberFormatException e) {
			logError("Not a number: " + propertyName(name) + "=" + v);
			return defaultValue;
		}
	}

	public ImapServer() {
		this(IMAP_PORT, IMAP_NAME, false);
	}

	public ImapServer(boolean secure) {
		this(secure ? IMAPS_PORT : IMAP_PORT, IMAP_NAME, secure);
	}

	public ImapServer(FileSource root, boolean secure) {
		this(secure);
		try {
			setMaildropRoot(root);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("Invalid maildrop root " + root, e);
		}
	}

	/**
	 * Run IMAP (and IMAP with implicit TLS when parley.mail.imaps.port is set) until the process is
	 * stopped; see parley-net's ServerMain.launch.
	 */
	public static void main(String[] args) throws Exception {
		ServerMain.launch("ImapServer", args, "imap", "imaps");
	}

	private void initMe() {
		setName("ImapServer");
		setProcessorFactory(new IProcessorFactory() {
			@Override
			public IProcessor getProcessor() {
				ImapRequestProcessor ret = new ImapRequestProcessor();
				ret.getLogger().setLevel(ImapServer.this.getLogger().getLevel());
				return ret;
			}
		});
		// sessions read the socket themselves and do their own autologout
		setMaxIdleConnection(Long.MAX_VALUE / 2);

		initAutologout(propertyName(AUTOLOGOUT), DEFAULT_AUTOLOGOUT);
		setRequireTls(getBooleanProperty(propertyName(REQUIRE_TLS), false));
		initMaildropRoot(FILE_SOURCE_PROP, null, ROOT_PROP, null, DEFAULT_ROOT, DEFAULT_ROOT_WINDOWS);

		warnIfNoAccessControl();
	}

	/** Unsaved flag changes are saved after the sessions end and while the store's locks still work. */
	@Override
	protected void releasingStore() {
		registry.saveAll();
	}

	// ------------------------------------------------------------------ mail

	public MailboxRegistry getRegistry() {
		return registry;
	}

	/**
	 * A user's INBOX directory: the principal's {@code maildrop} parameter, or the
	 * user name, inside the maildrop root (the same directory Pop3Server uses).
	 */
	public FileSource getInboxDirectory(IPrincipal principal) throws IOException {
		Object param = principal.getParameter(ImapRequestProcessor.PARAMETER_MAILDROP);
		return getInboxDirectory(param != null ? param.toString() : principal.getName());
	}

	public FileSource getInboxDirectory(String path) throws IOException {
		ArrayDeque<String> segments = new ArrayDeque<>();
		for (String s : path.replace('\\', '/').split("/")) {
			if (s.isEmpty() || s.equals(".")) {
				continue;
			}
			if (s.equals("..")) {
				segments.pollLast();
			} else {
				segments.add(s);
			}
		}
		if (segments.isEmpty()) {
			throw new IOException("Invalid maildrop path '" + path + "'");
		}
		FileSource dir = getMaildropRoot();
		for (String s : segments) {
			dir = dir.getChild(s);
		}
		return dir;
	}

	/** The mail store of a user. */
	public MailStore getMailStore(IPrincipal principal) throws IOException {
		MailStore store = new MailStore(getInboxDirectory(principal), registry);
		store.init(createDefaultMailboxes);
		return store;
	}

	// ------------------------------------------------------------------ settings

	public long getAppendLimit() {
		return appendLimit;
	}

	public void setAppendLimit(long appendLimit) {
		this.appendLimit = appendLimit;
	}

	public boolean isCreateDefaultMailboxes() {
		return createDefaultMailboxes;
	}

	public void setCreateDefaultMailboxes(boolean createDefaultMailboxes) {
		this.createDefaultMailboxes = createDefaultMailboxes;
	}
}
