package us.bringardner.parley.imap.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Properties;

import javax.net.ssl.SSLContext;

import us.bringardner.parley.core.ILogger.Level;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.net.IConnection;
import us.bringardner.parley.net.Connection;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.IConnectionFactory;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.IProcessorFactory;
import us.bringardner.parley.net.server.Server;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
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
 * ({@code ImapServer.AuthenticationProvider}): READ is needed to log in, WRITE
 * to change anything.
 */
public class ImapServer extends Server implements IMAP {

	private static final long serialVersionUID = 1L;

	public static final String IMAP_NAME = "JImap";
	public static final String CONFIG_PROP = IMAP_NAME + ".properties";
	/** The maildrop root; defaults to JPop3.root, so the two servers share mail. */
	public static final String ROOT_PROP = IMAP_NAME + ".root";
	public static final String FILE_SOURCE_PROP = IMAP_NAME + ".fileSource";
	public static final String DEFAULT_ROOT_WINDOWS = "C:/pop3";
	public static final String DEFAULT_ROOT = "/pop3";

	/** RFC 9051 section 5.4: at least 30 minutes. */
	public static final int DEFAULT_AUTOLOGOUT = 30 * 60 * 1000;
	public static final String AUTOLOGOUT_PROP = IMAP_NAME + ".autologout";
	public static final int DEFAULT_LOGIN_FAILURE_DELAY = 1000;
	/** Older name of the delay setting, still read: see {@link #getDefaultLoginFailureDelay()} */
	public static final String LOGIN_FAILURE_DELAY_PROP = IMAP_NAME + ".loginFailureDelay";
	/** Refuse LOGIN and AUTHENTICATE until STARTTLS (LOGINDISABLED). */
	public static final String REQUIRE_TLS_PROP = IMAP_NAME + ".requireTls";
	/** The largest message APPEND accepts. */
	public static final String APPEND_LIMIT_PROP = IMAP_NAME + ".appendLimit";
	public static final long DEFAULT_APPEND_LIMIT = 100L * 1024 * 1024;
	/** Create Sent, Drafts, Trash, Junk and Archive for a new user. */
	public static final String DEFAULT_MAILBOXES_PROP = IMAP_NAME + ".defaultMailboxes";

	private volatile int autologout = Integer.getInteger(AUTOLOGOUT_PROP, DEFAULT_AUTOLOGOUT);
	private volatile boolean requireTls = Boolean.getBoolean(REQUIRE_TLS_PROP);
	private volatile long appendLimit = Long.getLong(APPEND_LIMIT_PROP, DEFAULT_APPEND_LIMIT);
	private volatile boolean createDefaultMailboxes = Boolean
			.parseBoolean(System.getProperty(DEFAULT_MAILBOXES_PROP, "true"));

	private FileSource maildropRoot;
	private FileSourceFactory factory = FileSourceFactory.getDefaultFactory();
	private volatile Boolean tlsAvailable;
	private final MailboxRegistry registry = MailboxRegistry.get();

	private final class ServerConnection extends Connection {
		ServerConnection(Socket socket, boolean useCRLF, Level logLevel) throws IOException {
			super(socket, useCRLF);
			getLogger().setLevel(logLevel);
		}

		/** STARTTLS uses the server's key store. */
		@Override
		public SSLContext getSSLContext(String sslOrTls) throws IOException {
			return ImapServer.this.getSSLContext(sslOrTls);
		}
	}

	public ImapServer(int port, String name, boolean secure) {
		super(port, name);
		setPropertyPrefix("ImapServer");
		setSecure(secure);
		setDaemon(false);
		initMe();
		getLogger().setLevel(Level.INFO);
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

	public static void main(String[] args) throws Exception {
		System.out.println("\nStarting ImapServer with " + args.length + " args");
		for (int idx = 0; idx < args.length; idx++) {
			if (args[idx].startsWith("-D")) {
				String[] tmp = args[idx].substring(2).split("=", 2);
				if (tmp.length == 2) {
					System.out.println("\t" + tmp[0] + "=" + tmp[1]);
					System.setProperty(tmp[0], tmp[1]);
				} else {
					System.out.println("Invalid arg = " + args[idx]);
				}
			} else if (idx + 1 < args.length) {
				System.out.println("\t" + args[idx] + "=" + args[idx + 1]);
				System.setProperty(args[idx++], args[idx]);
			}
		}
		String tmp = System.getProperty(CONFIG_PROP);
		if (tmp != null) {
			System.out.println("Looking for " + tmp);
			Properties prop = System.getProperties();
			try (InputStream in = new FileInputStream(new File(tmp))) {
				prop.load(in);
			}
			System.out.println("Loaded properties from " + tmp);
		}
		boolean secure = Boolean.parseBoolean(System.getProperty(IMAP_NAME + ".secure", "false"));
		int port = Integer.getInteger(IMAP_NAME + ".port", secure ? IMAPS_PORT : IMAP_PORT);
		ImapServer server = new ImapServer(port, IMAP_NAME, secure);
		server.start();
		System.out.println("ImapServer started on port " + port);
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
		setConnectionFactory(new IConnectionFactory() {
			@Override
			public IConnection getConnection(Socket socket) throws IOException {
				return new ServerConnection(socket, true, ImapServer.this.getLogger().getLevel());
			}
		});
		// sessions read the socket themselves and do their own autologout
		setMaxIdleConnection(Long.MAX_VALUE / 2);

		String tmp = System.getProperty(FILE_SOURCE_PROP, System.getProperty("JPop3.fileSource"));
		if (tmp != null) {
			factory = FileSourceFactory.getFileSourceFactory(tmp.toLowerCase(Locale.ROOT));
		}
		tmp = System.getProperty(ROOT_PROP, System.getProperty("JPop3.root"));
		if (tmp == null) {
			tmp = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? DEFAULT_ROOT_WINDOWS : DEFAULT_ROOT;
		}
		try {
			maildropRoot = factory.createFileSource(tmp);
		} catch (IOException e) {
			logInfo("Error attempting to set the maildrop root " + tmp + " using the " + factory.getTypeId() + " factory");
		}

		IAccessControlList acl = getAccessControl();
		if (acl == null) {
			logInfo("No access control is configured for " + getName() + "; no one can log in");
		}
	}

	@Override
	public void stop() {
		super.stop();
		registry.saveAll();
	}

	// ------------------------------------------------------------------ mail

	public FileSource getMaildropRoot() throws IOException {
		if (maildropRoot == null) {
			throw new IOException("The maildrop root is not configured (see " + ROOT_PROP + ")");
		}
		if (!maildropRoot.exists()) {
			maildropRoot.mkdirs();
		}
		return maildropRoot;
	}

	public void setMaildropRoot(FileSource root) throws IOException {
		if (!root.exists()) {
			if (!root.mkdirs()) {
				throw new IOException("Can't create the maildrop root " + root);
			}
		} else if (!root.isDirectory()) {
			throw new IOException("The maildrop root is not a directory: " + root);
		}
		this.maildropRoot = root;
		this.factory = root.getFileSourceFactory();
	}

	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

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

	/**
	 * Deliver a message to a user's INBOX (or another mailbox), e.g. from an SMTP
	 * server. Sessions with the mailbox selected see it at once.
	 *
	 * @return the new message's UID
	 */
	public long deliver(String user, String mailbox, Message message, java.util.Set<String> flags) throws IOException {
		IAccessControlList acl = getAccessControl();
		IPrincipal p = acl == null ? null : acl.getPrincipal(user);
		MailStore store = new MailStore(p != null ? getInboxDirectory(p) : getInboxDirectory(user), registry);
		store.init(createDefaultMailboxes);
		Mailbox m = store.open(mailbox == null ? INBOX : mailbox);
		try {
			FileSource dir = message.getWorkDirectory();
			FileSource tmp = dir.getFileSourceFactory().createTempFile("imap", ".eml", dir);
			try {
				try (java.io.OutputStream out = new java.io.BufferedOutputStream(tmp.getOutputStream(), 64 * 1024)) {
					message.writeTo(out);
				}
				try (InputStream in = tmp.getInputStream()) {
					return m.append(in, flags == null ? java.util.Set.of() : flags, System.currentTimeMillis()).getUid();
				}
			} finally {
				tmp.delete();
			}
		} finally {
			registry.release(m);
		}
	}

	/** True if a TLS context can be created (STARTTLS is offered only then). */
	public boolean isTlsAvailable() {
		Boolean ret = tlsAvailable;
		if (ret == null) {
			try {
				// a key store must be configured: without keys a TLS handshake can only fail
				javax.net.ssl.KeyManager[] km = getKeyManagers();
				ret = km != null && km.length > 0 && getSSLContext("TLS") != null;
			} catch (Exception e) {
				logDebug("TLS is not available: " + e);
				ret = false;
			}
			tlsAvailable = ret;
		}
		return ret;
	}

	// ------------------------------------------------------------------ settings

	public int getAutologout() {
		return autologout;
	}

	/** Close sessions idle for this long (ms). RFC 9051 requires at least 30 minutes. */
	public void setAutologout(int autologout) {
		if (autologout <= 0) {
			throw new IllegalArgumentException("autologout must be positive");
		}
		this.autologout = autologout;
	}

	/**
	 * The shared LoginFailureDelay setting (see AbstractCoreServer) defaults to the older
	 * {@value #LOGIN_FAILURE_DELAY_PROP} system property, else {@value #DEFAULT_LOGIN_FAILURE_DELAY} ms.
	 */
	@Override
	protected int getDefaultLoginFailureDelay() {
		return Integer.getInteger(LOGIN_FAILURE_DELAY_PROP, DEFAULT_LOGIN_FAILURE_DELAY);
	}

	public boolean isRequireTls() {
		return requireTls;
	}

	/** Refuse logins until STARTTLS (LOGINDISABLED is advertised). */
	public void setRequireTls(boolean requireTls) {
		this.requireTls = requireTls;
	}

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
