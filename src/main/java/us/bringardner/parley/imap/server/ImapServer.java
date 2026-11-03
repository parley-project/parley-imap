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
import us.bringardner.parley.mail.store.ShareIndex;

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
 * <p>
 * Each mailbox has an access control list (RFC 4314, {@link us.bringardner.parley.mail.store.MailboxAcl}):
 * SETACL, DELETEACL, GETACL, LISTRIGHTS and MYRIGHTS manage it, and every command checks the
 * rights it needs. The server's permissions are a ceiling (no WRITE: read only everywhere).
 * Mailboxes other users share are in the Other Users namespace,
 * {@code Other Users/bob@bringardner.us/Projects} ({@code Other Users/bob@bringardner.us} is
 * bob's INBOX); LIST shows one only where the user has the {@code l} right. A mail
 * administrator (ADMIN) has no special rights here.
 */
public class ImapServer extends AbstractMailServer implements IMAP {


	public static final String IMAP_NAME = "JImap";
	/** The prefix of the properties of an IMAP server (port 143, STARTTLS). */
	public static final String IMAP_PREFIX = "parley.mail.imap";
	/** The prefix of the properties of an IMAP server with implicit TLS (port 993). */
	public static final String IMAPS_PREFIX = "parley.mail.imaps";
	/** A settings file for the server's configuration (the file its main reads, or a launcher's). */
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
	 * Run IMAP (parley.mail.imap.port, default 143) and, when there is a TLS key store, IMAP with
	 * implicit TLS (parley.mail.imaps.port, default 993) in this process until it is stopped, with
	 * parley-net's ServerMain.run: the settings are parley-imap.properties in the settings folder (or
	 * the file parley.mail.imap.configFile names) and the command line, read as system properties.
	 * Each server's store is the JMX bean us.bringardner.parley:type=MailStore,name=<its id> while it
	 * runs (MailServerMain).
	 */
	public static void main(String[] args) throws Exception {
		ServerMain.run("ImapServer", args, CONFIG_PROP, "parley-imap.properties", () -> {
			java.util.List<ServerMain.Service> ret = new java.util.ArrayList<>();
			{
				int p = standalonePort(IMAP_PREFIX, IMAP.IMAP_PORT);
				ret.add(us.bringardner.parley.mail.server.MailServerMain.service(new ImapServer(p, IMAP_NAME, false), "IMAP server", "imap"));
			}
			if (hasKeyStore(IMAPS_PREFIX)) {
				int p = standalonePort(IMAPS_PREFIX, IMAP.IMAPS_PORT);
				ret.add(us.bringardner.parley.mail.server.MailServerMain.service(new ImapServer(p, IMAP_NAME, true), "IMAP server (implicit TLS)", "imaps"));
			} else {
				System.err.println("Warning: IMAP server (implicit TLS) is skipped: no TLS key store (set " + MAIL_TLS_KEY_STORE_PROP + " or " + IMAPS_PREFIX + ".keyStoreName)");
			}
			return ret;
		});
	}

	/** A server's port in a standalone process: {@code <prefix>.port}, else its standard port */
	private static int standalonePort(String prefix, int standard) {
		String v = System.getProperty(prefix + ".port");
		return v == null || v.trim().isEmpty() ? standard : Integer.parseInt(v.trim());
	}

	/** A TLS key store for this server in a standalone process: the suite's, or its own */
	private static boolean hasKeyStore(String prefix) {
		String a = System.getProperty(MAIL_TLS_KEY_STORE_PROP);
		String b = System.getProperty(prefix + ".keyStoreName");
		return (a != null && !a.trim().isEmpty()) || (b != null && !b.trim().isEmpty());
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

	/** The mail store of a user who logs in: made (with the default mailboxes) if it isn't there yet. */
	public MailStore getMailStore(IPrincipal principal) throws IOException {
		MailStore store = openMailStore(principal);
		store.init(createDefaultMailboxes);
		return store;
	}

	/**
	 * Another user's mail store, as it is (nothing is made), for the Other Users namespace.
	 * @since 2.0.0
	 */
	public MailStore openMailStore(IPrincipal principal) throws IOException {
		MailStore store = new MailStore(getInboxDirectory(principal), registry, principal.getName());
		store.setShareIndex(getShareIndex());
		return store;
	}

	/**
	 * The maildrop root's share index: which users' mailboxes are shared with whom (RFC 4314 ACLs).
	 * @since 2.0.0
	 */
	public ShareIndex getShareIndex() throws IOException {
		return new ShareIndex(getMaildropRoot(), registry);
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
