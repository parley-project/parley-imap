package us.bringardner.parley.imap.server;

import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.mail.server.AbstractMailServer;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;



import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.IProcessorFactory;
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
public class ImapServer extends AbstractMailServer implements IMAP {

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

	private volatile long appendLimit = Long.getLong(APPEND_LIMIT_PROP, DEFAULT_APPEND_LIMIT);
	private volatile boolean createDefaultMailboxes = Boolean
			.parseBoolean(System.getProperty(DEFAULT_MAILBOXES_PROP, "true"));

	private final MailboxRegistry registry = MailboxRegistry.get();

	public ImapServer(int port, String name, boolean secure) {
		super(port, name, "ImapServer", secure);
		initMe();
		finishInit();
	}

	@Override
	protected String getRootProperty() {
		return ROOT_PROP;
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
		ServerMain.configure("ImapServer", args, CONFIG_PROP);
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
		// sessions read the socket themselves and do their own autologout
		setMaxIdleConnection(Long.MAX_VALUE / 2);

		initAutologout(AUTOLOGOUT_PROP, DEFAULT_AUTOLOGOUT);
		setRequireTls(Boolean.getBoolean(REQUIRE_TLS_PROP));
		initMaildropRoot(FILE_SOURCE_PROP, "JPop3.fileSource", ROOT_PROP, "JPop3.root", DEFAULT_ROOT, DEFAULT_ROOT_WINDOWS);

		warnIfNoAccessControl();
	}

	@Override
	public void stop() {
		super.stop();
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

	// ------------------------------------------------------------------ settings

	/**
	 * The shared LoginFailureDelay setting (see AbstractCoreServer) defaults to the older
	 * {@value #LOGIN_FAILURE_DELAY_PROP} system property, else {@value #DEFAULT_LOGIN_FAILURE_DELAY} ms.
	 */
	@Override
	protected int getDefaultLoginFailureDelay() {
		return Integer.getInteger(LOGIN_FAILURE_DELAY_PROP, DEFAULT_LOGIN_FAILURE_DELAY);
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
