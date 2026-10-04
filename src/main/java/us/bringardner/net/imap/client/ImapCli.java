package us.bringardner.net.imap.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import us.bringardner.net.email.Address;
import us.bringardner.net.framework.client.DynamicTrustManager;
import us.bringardner.net.framework.client.DynamicTrustManager.CertificateValidator.ManageAs;
import us.bringardner.core.util.Hex;

/**
 * A command-line IMAP client built on {@link ImapClient}: an interactive shell,
 * a script on standard input, or one command from the command line.
 *
 * <pre>
 * java -cp ... us.bringardner.net.imap.client.ImapCli --host imap.example.com --user tony
 * java -cp ... us.bringardner.net.imap.client.ImapCli --host imap.example.com --user tony ls INBOX 20
 * </pre>
 *
 * Type {@code help} in the shell for the commands.
 */
public class ImapCli {

	private static final String USAGE = "Usage: ImapCli --host HOST [options] [command [args...]]\n"
			+ "Options:\n"
			+ "  --host HOST         the IMAP server\n"
			+ "  --port PORT         default 993 with --tls, else 143\n"
			+ "  --tls               implicit TLS (the default)\n"
			+ "  --starttls          plain connection upgraded with STARTTLS (required)\n"
			+ "  --plain             no encryption (STARTTLS is still used if offered)\n"
			+ "  --insecure          no encryption at all (testing only)\n"
			+ "  --user USER         log in as USER\n"
			+ "  --password PASS     the password (else $IMAP_PASSWORD, else prompted)\n"
			+ "  --trust-all         accept any server certificate (testing only)\n"
			+ "  --timeout SECONDS   how long to wait for the server (default 60)\n"
			+ "  --rev1              don't enable IMAP4rev2\n"
			+ "  --trace             show the protocol (passwords hidden)\n"
			+ "  --help              this text\n"
			+ "Without a command, commands are read from standard input (type 'help').";

	private static final String HELP = String.join("\n",
			"Mailboxes:",
			"  list [pattern]              mailboxes (pattern: * all, % one level)",
			"  lsub                        subscribed mailboxes",
			"  status MAILBOX              message counts",
			"  select MAILBOX              open a mailbox (examine: read-only)",
			"  examine MAILBOX",
			"  create MAILBOX | delete MAILBOX | rename OLD NEW",
			"  subscribe MAILBOX | unsubscribe MAILBOX",
			"  close                       leave the mailbox (removes \\Deleted messages)",
			"Messages (in the selected mailbox, by UID; UIDS like 5 or 3:7,9 or 1:*):",
			"  ls [COUNT]                  the newest messages (default 20)",
			"  show UID                    headers, text and attachments (marks it read)",
			"  parts UID                   the MIME structure",
			"  save UID FILE               the whole message (.eml)",
			"  get UID PART [FILE]         one part, decoded (default file: its name)",
			"  search CRITERIA...          e.g. search UNSEEN FROM \"fred\" SINCE 1-Oct-2026",
			"  flag UIDS FLAG... | unflag UIDS FLAG...   e.g. flag 5 \\Flagged",
			"  read UIDS | unread UIDS",
			"  rm UIDS                     delete now (\\Deleted + UID EXPUNGE)",
			"  expunge                     remove all \\Deleted messages",
			"  cp UIDS MAILBOX | mv UIDS MAILBOX",
			"  put MAILBOX FILE [FLAG...]  append a message file (e.g. to Sent)",
			"Other:",
			"  idle [SECONDS]              wait for new mail (Enter stops it)",
			"  caps                        server capabilities",
			"  noop                        check for changes",
			"  raw COMMAND...              send any command",
			"  trace on|off",
			"  quit");

	private static final DateTimeFormatter LIST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);

	private final ImapClient client;
	private final BufferedReader in;
	private final PrintStream out;
	private final ZoneId zone;

	public ImapCli(ImapClient client, BufferedReader in, PrintStream out) {
		this(client, in, out, ZoneId.systemDefault());
	}

	public ImapCli(ImapClient client, BufferedReader in, PrintStream out, ZoneId zone) {
		this.client = client;
		this.in = in;
		this.out = out;
		this.zone = zone;
		client.addListener(new ImapListener() {
			@Override
			public void exists(String mailbox, long count) {
				out.println("* " + mailbox + ": " + count + " messages");
			}

			@Override
			public void expunged(String mailbox, long msn, long uid) {
				out.println("* " + mailbox + ": message " + (uid >= 0 ? "UID " + uid : "#" + msn) + " removed");
			}

			@Override
			public void flagsChanged(String mailbox, long msn, long uid, Set<String> flags) {
				out.println("* " + mailbox + ": UID " + uid + " flags " + flags);
			}

			@Override
			public void alert(String text) {
				out.println("* ALERT: " + text);
			}

			@Override
			public void bye(String text) {
				out.println("* The server is closing the connection: " + text);
			}
		});
	}

	// ------------------------------------------------------------------ main

	public static void main(String[] args) {
		System.exit(run(args, System.in, System.out, System.err));
	}

	/** Run the program; returns the exit code (0 ok, 1 a command failed, 2 usage). */
	public static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
		ImapClientConfig config = new ImapClientConfig();
		ImapClientConfig.Security security = ImapClientConfig.Security.TLS;
		int port = -1;
		String user = null;
		String password = null;
		String host = null;
		boolean trace = false;
		List<String> command = new ArrayList<>();
		try {
			for (int i = 0; i < args.length; i++) {
				String a = args[i];
				if (!command.isEmpty() || !a.startsWith("--")) {
					command.add(a);
					continue;
				}
				switch (a) {
				case "--host":
					host = value(args, ++i, a);
					config.setHost(host);
					break;
				case "--port":
					port = Integer.parseInt(value(args, ++i, a));
					break;
				case "--tls":
					security = ImapClientConfig.Security.TLS;
					break;
				case "--starttls":
					security = ImapClientConfig.Security.STARTTLS;
					break;
				case "--plain":
					security = ImapClientConfig.Security.STARTTLS_IF_AVAILABLE;
					break;
				case "--insecure":
					security = ImapClientConfig.Security.NONE;
					break;
				case "--user":
					user = value(args, ++i, a);
					break;
				case "--password":
					password = value(args, ++i, a);
					break;
				case "--trust-all":
					config.setTrustAllCertificates(true);
					break;
				case "--timeout":
					config.setReadTimeout(Integer.parseInt(value(args, ++i, a)) * 1000);
					break;
				case "--rev1":
					config.setEnableRev2(false);
					break;
				case "--trace":
					trace = true;
					break;
				case "--help":
					out.println(USAGE);
					return 0;
				default:
					throw new IllegalArgumentException("Unknown option " + a);
				}
			}
			if (host == null) {
				throw new IllegalArgumentException("--host is required");
			}
		} catch (RuntimeException e) {
			err.println(e.getMessage());
			err.println(USAGE);
			return 2;
		}
		config.setSecurity(security);
		config.setPort(port > 0 ? port : security == ImapClientConfig.Security.TLS ? 993 : 143);

		BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
		if (!config.isTrustAllCertificates()) {
			config.setCertificateValidator(new ConsoleValidator(err));
		}
		try (ImapClient client = new ImapClient(config)) {
			ImapCli cli = new ImapCli(client, reader, out);
			if (trace) {
				client.setTrace(err::println);
			}
			client.connect();
			if (command.isEmpty()) {
				out.println("Connected to " + config.getHost() + ":" + config.getPort());
			}
			if (user != null) {
				if (password == null) {
					password = System.getenv("IMAP_PASSWORD");
				}
				if (password == null) {
					password = readPassword(reader, err, "Password for " + user + ": ");
				}
				client.login(user, password);
			}
			if (!command.isEmpty()) {
				return cli.execute(command) ? 0 : 1;
			}
			return cli.shell(stdin == System.in && System.console() != null) ? 0 : 1;
		} catch (IOException | RuntimeException e) {
			err.println("Error: " + message(e));
			return 1;
		}
	}

	private static String value(String[] args, int i, String option) {
		if (i >= args.length) {
			throw new IllegalArgumentException(option + " needs a value");
		}
		return args[i];
	}

	private static String readPassword(BufferedReader reader, PrintStream err, String prompt) throws IOException {
		Console c = System.console();
		if (c != null) {
			char[] p = c.readPassword("%s", prompt);
			return p == null ? "" : new String(p);
		}
		err.print(prompt);
		err.flush();
		String line = reader.readLine();
		return line == null ? "" : line;
	}

	/**
	 * Asks on the terminal whether to trust a certificate the system doesn't
	 * ("always" is remembered by the framework). Without a terminal (a script on
	 * standard input) the certificate is rejected; use --trust-all for test servers.
	 */
	static final class ConsoleValidator implements DynamicTrustManager.CertificateValidator {
		private final PrintStream out;

		ConsoleValidator(PrintStream out) {
			this.out = out;
		}

		@Override
		public ManageAs validate(X509Certificate cert) {
			return validate(cert, null);
		}

		@Override
		public ManageAs validate(X509Certificate cert, String host) {
			out.println("The server's certificate isn't trusted" + (host == null ? "" : " for " + host) + ":");
			out.println("  Subject:  " + cert.getSubjectX500Principal().getName());
			out.println("  Issuer:   " + cert.getIssuerX500Principal().getName());
			out.println("  Valid:    " + cert.getNotBefore() + " to " + cert.getNotAfter());
			try {
				byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
				out.println("  SHA-256:  " + Hex.encode(d, true, ":"));
			} catch (Exception e) {
				// no fingerprint
			}
			Console console = System.console();
			if (console == null) {
				out.println("Rejected (no terminal to ask; --trust-all accepts any certificate)");
				return ManageAs.REJECT;
			}
			String a = console.readLine("Trust it? [n]o, [o]nce, [a]lways: ");
			a = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
			if (a.startsWith("a")) {
				return ManageAs.ACCEPT_ALWAYS;
			}
			if (a.startsWith("o")) {
				return ManageAs.ACCEPT_ONCE;
			}
			return ManageAs.REJECT;
		}
	}

	// ------------------------------------------------------------------ shell

	/**
	 * Read and run commands until "quit" or the end of input.
	 *
	 * @return false if any command failed
	 */
	public boolean shell(boolean interactive) throws IOException {
		boolean ok = true;
		while (client.isConnected()) {
			if (interactive) {
				SelectedMailbox s = client.getSelected();
				out.print((s == null ? "imap" : s.getName()) + "> ");
				out.flush();
			}
			String line = in.readLine();
			if (line == null) {
				break;
			}
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			List<String> words = split(line);
			String verb = words.get(0).toLowerCase(Locale.ROOT);
			if (verb.equals("quit") || verb.equals("exit") || verb.equals("logout")) {
				break;
			}
			ok &= execute(words);
		}
		if (client.isConnected()) {
			client.logout();
		}
		return ok;
	}

	/** Run one command line. */
	public boolean execute(String line) {
		return execute(split(line));
	}

	/** Run one command; errors are printed. Returns false if it failed. */
	public boolean execute(List<String> words) {
		if (words.isEmpty()) {
			return true;
		}
		String verb = words.get(0).toLowerCase(Locale.ROOT);
		List<String> a = words.subList(1, words.size());
		try {
			dispatch(verb, a);
			return true;
		} catch (IllegalArgumentException e) {
			out.println("Usage error: " + e.getMessage());
		} catch (ImapException e) {
			out.println("Failed: " + e.getStatus() + (e.getCode() == null ? "" : " [" + e.getCode() + "]") + " "
					+ e.getServerText());
		} catch (IOException | RuntimeException e) {
			out.println("Error: " + message(e));
		}
		return false;
	}

	private void dispatch(String verb, List<String> a) throws IOException {
		switch (verb) {
		case "help":
		case "?":
			out.println(HELP);
			break;
		case "caps":
		case "capability":
			out.println(String.join(" ", client.capability()));
			break;
		case "list":
			for (MailboxInfo m : client.list("", a.isEmpty() ? "*" : a.get(0))) {
				printMailbox(m);
			}
			break;
		case "lsub":
			for (MailboxInfo m : client.listSubscribed()) {
				printMailbox(m);
			}
			break;
		case "status": {
			need(a, 1, "status MAILBOX");
			MailboxStatus s = client.status(a.get(0));
			out.println(a.get(0) + ": " + s.getMessages() + " messages, " + s.getUnseen() + " unseen, UIDNEXT "
					+ s.getUidNext() + ", UIDVALIDITY " + s.getUidValidity()
					+ (s.getSize() >= 0 ? ", " + size(s.getSize()) : ""));
			break;
		}
		case "select":
		case "cd":
		case "examine": {
			need(a, 1, verb + " MAILBOX");
			SelectedMailbox s = verb.equals("examine") ? client.examine(a.get(0)) : client.select(a.get(0));
			out.println(s.getName() + ": " + s.getExists() + " messages" + (s.isReadOnly() ? " (read-only)" : ""));
			break;
		}
		case "close":
			client.closeMailbox();
			break;
		case "create":
		case "mkdir":
			need(a, 1, "create MAILBOX");
			client.create(a.get(0));
			out.println("Created " + a.get(0));
			break;
		case "delete":
		case "rmdir":
			need(a, 1, "delete MAILBOX");
			client.delete(a.get(0));
			out.println("Deleted " + a.get(0));
			break;
		case "rename":
			need(a, 2, "rename OLD NEW");
			client.rename(a.get(0), a.get(1));
			break;
		case "subscribe":
			need(a, 1, "subscribe MAILBOX");
			client.subscribe(a.get(0));
			break;
		case "unsubscribe":
			need(a, 1, "unsubscribe MAILBOX");
			client.unsubscribe(a.get(0));
			break;
		case "ls":
			ls(a.isEmpty() ? 20 : Integer.parseInt(a.get(0)));
			break;
		case "show":
		case "cat":
			need(a, 1, "show UID");
			show(uid(a.get(0)));
			break;
		case "parts":
			need(a, 1, "parts UID");
			parts(uid(a.get(0)));
			break;
		case "save": {
			need(a, 2, "save UID FILE");
			File f = new File(a.get(1));
			try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
				long n = client.fetchMessage(uid(a.get(0)), o, false, null);
				out.println("Saved " + size(n) + " to " + f);
			}
			break;
		}
		case "get":
			need(a, 2, "get UID PART [FILE]");
			getPart(uid(a.get(0)), a.get(1), a.size() > 2 ? a.get(2) : null);
			break;
		case "search": {
			need(a, 1, "search CRITERIA...");
			List<Long> uids = client.search(String.join(" ", quoteArgs(a)));
			out.println(uids.isEmpty() ? "No messages" : uids.size() + " found: " + ImapClient.uidSet(uids));
			break;
		}
		case "flag":
			need(a, 2, "flag UIDS FLAG...");
			client.addFlags(a.get(0), a.subList(1, a.size()).toArray(new String[0]));
			break;
		case "unflag":
			need(a, 2, "unflag UIDS FLAG...");
			client.removeFlags(a.get(0), a.subList(1, a.size()).toArray(new String[0]));
			break;
		case "read":
			need(a, 1, "read UIDS");
			client.addFlags(a.get(0), "\\Seen");
			break;
		case "unread":
			need(a, 1, "unread UIDS");
			client.removeFlags(a.get(0), "\\Seen");
			break;
		case "rm":
			need(a, 1, "rm UIDS");
			client.deleteMessages(a.get(0));
			break;
		case "expunge":
			client.expunge();
			break;
		case "cp":
		case "copy": {
			need(a, 2, "cp UIDS MAILBOX");
			ImapClient.CopyResult r = client.copy(a.get(0), a.get(1));
			out.println("Copied" + newUids(r));
			break;
		}
		case "mv":
		case "move": {
			need(a, 2, "mv UIDS MAILBOX");
			ImapClient.CopyResult r = client.move(a.get(0), a.get(1));
			out.println("Moved" + newUids(r));
			break;
		}
		case "put":
		case "append": {
			need(a, 2, "put MAILBOX FILE [FLAG...]");
			File f = new File(a.get(1));
			if (!f.isFile()) {
				throw new IllegalArgumentException("No file " + f);
			}
			try (InputStream fin = new BufferedInputStream(new FileInputStream(f))) {
				long uid = client.append(a.get(0), fin, f.length(), a.subList(2, a.size()),
						Instant.ofEpochMilli(f.lastModified()), null);
				out.println("Appended " + size(f.length()) + (uid > 0 ? " as UID " + uid : ""));
			}
			break;
		}
		case "idle":
			idle(a.isEmpty() ? 0 : Integer.parseInt(a.get(0)));
			break;
		case "noop":
			client.noop();
			break;
		case "raw":
			need(a, 1, "raw COMMAND...");
			for (Response r : client.execute(String.join(" ", a))) {
				out.println(r);
			}
			break;
		case "trace":
			need(a, 1, "trace on|off");
			client.setTrace(a.get(0).equalsIgnoreCase("on") ? out::println : null);
			break;
		default:
			throw new IllegalArgumentException("Unknown command " + verb + " (type help)");
		}
	}

	private static void need(List<String> a, int n, String usage) {
		if (a.size() < n) {
			throw new IllegalArgumentException(usage);
		}
	}

	private static long uid(String s) {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not a UID: " + s);
		}
	}

	private static String newUids(ImapClient.CopyResult r) {
		return r.uids.isEmpty() ? "" : " (new UIDs " + ImapClient.uidSet(r.uids.values()) + ")";
	}

	private void printMailbox(MailboxInfo m) {
		StringBuilder sb = new StringBuilder(m.getName());
		if (m.getSpecialUse() != null) {
			sb.append("  ").append(m.getSpecialUse());
		}
		if (!m.isSelectable()) {
			sb.append("  (not selectable)");
		}
		out.println(sb);
	}

	// ------------------------------------------------------------------ messages

	private void ls(int count) throws IOException {
		SelectedMailbox s = requireSelected();
		long exists = s.getExists();
		if (exists == 0) {
			out.println("No messages");
			return;
		}
		long first = Math.max(1, exists - count + 1);
		List<MessageSummary> list = client.fetchSummariesBySequence(first + ":" + exists);
		for (MessageSummary m : list) {
			out.println(line(m));
		}
		out.println(list.size() + " of " + exists + " messages");
	}

	String line(MessageSummary m) {
		String marks = (m.isSeen() ? " " : "N") + (m.isFlagged() ? "F" : " ") + (m.isAnswered() ? "A" : " ")
				+ (m.isDeleted() ? "D" : " ") + (m.hasAttachments() ? "@" : " ");
		Envelope e = m.getEnvelope();
		Instant when = e != null && e.getDate() != null ? e.getDate() : m.getInternalDate();
		String from = "";
		if (e != null && !e.getFrom().isEmpty()) {
			Address f = e.getFrom().get(0);
			from = f.getDisplayName() != null && !f.getDisplayName().trim().isEmpty() ? f.getDisplayName()
					: f.toUtf8String();
		}
		String subject = e == null || e.getSubject() == null ? "" : e.getSubject();
		return String.format("%6d %s %s  %-20s  %-40s %8s", m.getUid(), marks,
				when == null ? "????-??-?? ??:??" : LIST_DATE.format(when.atZone(zone)), cut(from, 20), cut(subject, 40),
				size(m.getSize()));
	}

	private MessageSummary summary(long uid) throws IOException {
		List<MessageSummary> l = client.fetchSummaries(Long.toString(uid));
		if (l.isEmpty()) {
			throw new IllegalArgumentException("No message with UID " + uid);
		}
		return l.get(0);
	}

	private void show(long uid) throws IOException {
		MessageSummary m = summary(uid);
		Envelope e = m.getEnvelope();
		if (e != null) {
			out.println("From:    " + addresses(e.getFrom()));
			if (!e.getTo().isEmpty()) {
				out.println("To:      " + addresses(e.getTo()));
			}
			if (!e.getCc().isEmpty()) {
				out.println("Cc:      " + addresses(e.getCc()));
			}
			Instant d = e.getDate();
			if (d != null || e.getDateText() != null) {
				out.println("Date:    " + (d == null ? e.getDateText() : LONG_DATE.format(d.atZone(zone))));
			}
			out.println("Subject: " + (e.getSubject() == null ? "" : e.getSubject()));
		}
		out.println();
		BodyPart root = m.getStructure();
		BodyPart text = root == null ? null : root.findText("PLAIN");
		boolean html = false;
		if (text == null && root != null) {
			text = root.findText("HTML");
			html = text != null;
		}
		if (text != null) {
			String body = client.fetchText(uid, text);
			out.println(html ? stripHtml(body) : body);
		} else {
			out.println("(no text part)");
		}
		if (root != null) {
			for (BodyPart p : root.flatten()) {
				if (p.isAttachment()) {
					out.println("[part " + p.getPartNumber() + "] " + (p.getFilename() == null ? "(no name)" : p.getFilename())
							+ "  " + p.getMimeType() + "  " + size(p.getSize()));
				}
			}
		}
		SelectedMailbox s = client.getSelected();
		if (!m.isSeen() && s != null && !s.isReadOnly()) {
			client.addFlags(Long.toString(uid), "\\Seen");
		}
	}

	private void parts(long uid) {
		try {
			BodyPart root = summary(uid).getStructure();
			if (root == null) {
				out.println("(no structure)");
				return;
			}
			for (BodyPart p : root.flatten()) {
				int depth = p.getPartNumber().isEmpty() ? 0 : p.getPartNumber().split("\\.").length;
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < depth; i++) {
					sb.append("  ");
				}
				sb.append(p.getPartNumber().isEmpty() ? "-" : p.getPartNumber()).append("  ").append(p.getMimeType());
				if (!p.isMultipart()) {
					sb.append("  ").append(size(p.getSize())).append("  ").append(p.getEncoding().toLowerCase(Locale.ROOT));
				}
				if (p.getFilename() != null) {
					sb.append("  \"").append(p.getFilename()).append('"');
				}
				if (p.isAttachment()) {
					sb.append("  (attachment)");
				}
				out.println(sb);
			}
		} catch (IOException e) {
			throw new RuntimeException(message(e), e);
		}
	}

	private void getPart(long uid, String number, String fileName) throws IOException {
		BodyPart part = null;
		BodyPart root = summary(uid).getStructure();
		if (root != null) {
			for (BodyPart p : root.flatten()) {
				if (p.getPartNumber().equals(number)) {
					part = p;
				}
			}
		}
		if (part == null) {
			throw new IllegalArgumentException("No part " + number + " (see: parts " + uid + ")");
		}
		String name = fileName != null ? fileName : part.getFilename();
		if (name == null) {
			name = "part-" + uid + "-" + number;
		}
		File f = new File(name);
		if (fileName == null) {
			f = new File(f.getName()); // never a path from the message
		}
		try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
			long n = client.fetchPart(uid, part, o, null);
			out.println("Saved " + size(n) + " to " + f);
		}
	}

	private void idle(int seconds) throws IOException {
		requireSelected();
		client.startIdle();
		out.println("Waiting for changes" + (seconds > 0 ? " for " + seconds + "s" : "") + "; press Enter to stop");
		long end = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : Long.MAX_VALUE;
		try {
			while (client.isConnected() && System.currentTimeMillis() < end) {
				if (in.ready()) {
					in.readLine();
					break;
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		} finally {
			client.stopIdle();
		}
	}

	private SelectedMailbox requireSelected() {
		SelectedMailbox s = client.getSelected();
		if (s == null) {
			throw new IllegalArgumentException("Select a mailbox first");
		}
		return s;
	}

	// ------------------------------------------------------------------ text helpers

	/** Split a line into words; "double quotes" group words (\" and \\ inside). */
	static List<String> split(String line) {
		List<String> ret = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		boolean quoted = false;
		boolean any = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '\\' && i + 1 < line.length() && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
					sb.append(line.charAt(++i));
				} else if (c == '"') {
					quoted = false;
				} else {
					sb.append(c);
				}
			} else if (c == '"') {
				quoted = true;
				any = true;
			} else if (Character.isWhitespace(c)) {
				if (any) {
					ret.add(sb.toString());
					sb.setLength(0);
					any = false;
				}
			} else {
				sb.append(c);
				any = true;
			}
		}
		if (any) {
			ret.add(sb.toString());
		}
		return ret;
	}

	/** Search words back to IMAP: words with spaces or quotes become quoted strings. */
	static List<String> quoteArgs(List<String> words) {
		List<String> ret = new ArrayList<>();
		for (String w : words) {
			if (w.isEmpty() || w.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\\')) {
				ret.add("\"" + w.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
			} else {
				ret.add(w);
			}
		}
		return ret;
	}

	private static String addresses(List<Address> list) {
		List<String> s = new ArrayList<>();
		for (Address a : list) {
			s.add(a.toUtf8String());
		}
		return String.join(", ", s);
	}

	static String size(long n) {
		if (n < 0) {
			return "";
		}
		if (n < 1024) {
			return n + " B";
		}
		if (n < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
		}
		return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
	}

	private static String cut(String s, int n) {
		s = s.replaceAll("[\\r\\n\\t]+", " ");
		return s.length() <= n ? s : s.substring(0, n - 1) + "~";
	}

	static String stripHtml(String html) {
		String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
				.replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>|</h[1-6]>", "\n")
				.replaceAll("<[^>]*>", "")
				.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
				.replace("&#39;", "'").replace("&amp;", "&");
		return s.replaceAll("\n{3,}", "\n\n").trim();
	}

	private static String message(Exception e) {
		String m = e.getMessage();
		return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
	}

}
