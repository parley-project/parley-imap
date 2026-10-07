package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.pop3.server.Maildrop;
import us.bringardner.parley.pop3.server.Pop3Server;
import us.bringardner.parley.pop3.test.TestPop3Server;

/**
 * End-to-end tests of ImapServer over real sockets: IMAP4rev1 and IMAP4rev2
 * clients, two sessions on one mailbox, mail shared with Pop3Server, and a
 * message larger than the test heap.
 */
public class TestImapServer {

	private static final String KEYSTORE = "target/pop3keystore.p12";

	private static ImapServer server;
	private static Pop3Server pop3;
	private static FileSource root;
	private static int port;

	@BeforeAll
	public static void startServer() throws Exception {
		TestPop3Server.makeTestKeystore(new File(KEYSTORE));
		for (String prefix : new String[] {"ImapServer", "Pop3Server"}) {
			System.setProperty(prefix + ".KeyStoreName", KEYSTORE);
			System.setProperty(prefix + ".KeyStorePassword", TestPop3Server.KEYSTORE_PASSWORD);
			System.setProperty(prefix + ".KeyStoreType", "PKCS12");
			System.setProperty(prefix + "." + FileBasedAcl.PROP_FILE_NAME, "Pop3TestAcl.txt");
			System.setProperty(prefix + "." + IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getName());
		}
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("imaptest");
		server = new ImapServer(0, ImapServer.IMAP_NAME, false);
		server.setMaildropRoot(root);
		server.setLoginFailureDelay(0);
		server.getLogger().setLevel(Level.ERROR);
		server.startAndWait(10000);
		port = server.getLocalPort();

		pop3 = new Pop3Server(0, Pop3Server.POP3_NAME, false);
		pop3.setMaildropRoot(root);
		pop3.setLoginFailureDelay(0);
		pop3.getLogger().setLevel(Level.ERROR);
		pop3.startAndWait(10000);
	}

	@AfterAll
	public static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
		if (pop3 != null) {
			pop3.stop();
		}
		if (root != null) {
			deleteAll(root);
		}
	}

	private static void deleteAll(FileSource f) throws IOException {
		if (f.isDirectory()) {
			for (FileSource kid : f.listFiles()) {
				deleteAll(kid);
			}
		}
		f.delete();
	}

	/** Start a user with an empty maildrop (no other session may be using it). */
	private static FileSource reset(String maildrop) throws IOException {
		FileSource dir = server.getInboxDirectory(maildrop);
		if (dir.exists()) {
			deleteAll(dir);
		}
		return dir;
	}

	private static ImapClient connect() throws IOException {
		return new ImapClient(port);
	}

	private static ImapClient login(String user, String password) throws IOException {
		ImapClient c = connect();
		c.login(user, password);
		return c;
	}

	static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	static final String SIMPLE = "From: Fred Foo <fred@example.com>\r\n"
			+ "To: Tony <tony@bringardner.us>\r\n"
			+ "Subject: Test 1\r\n"
			+ "Date: Fri, 02 Oct 2026 15:00:00 -0400\r\n"
			+ "Message-ID: <m1@example.com>\r\n"
			+ "\r\n"
			+ "Hello\r\n"
			+ "World\r\n";

	static final String MULTIPART = "From: a@example.com\r\n"
			+ "Subject: parts\r\n"
			+ "MIME-Version: 1.0\r\n"
			+ "Content-Type: multipart/mixed; boundary=\"b1\"\r\n"
			+ "\r\n"
			+ "preamble\r\n"
			+ "--b1\r\n"
			+ "Content-Type: text/plain; charset=utf-8\r\n"
			+ "Content-Transfer-Encoding: quoted-printable\r\n"
			+ "\r\n"
			+ "H=C3=A4llo Welt\r\n"
			+ "--b1\r\n"
			+ "Content-Type: application/octet-stream; name=\"data.bin\"\r\n"
			+ "Content-Disposition: attachment; filename=\"data.bin\"\r\n"
			+ "Content-Transfer-Encoding: base64\r\n"
			+ "\r\n"
			+ "AAECAwQ=\r\n"
			+ "--b1\r\n"
			+ "Content-Type: message/rfc822\r\n"
			+ "\r\n"
			+ "Subject: inner\r\n"
			+ "From: b@example.com\r\n"
			+ "\r\n"
			+ "inner body\r\n"
			+ "--b1--\r\n";

	static final String UTF8 = "From: José <josé@exämple.com>\r\n"
			+ "To: tony@bringardner.us\r\n"
			+ "Subject: Grüße\r\n"
			+ "\r\n"
			+ "body\r\n";

	private static String find(List<String> responses, String prefix) {
		for (String r : responses) {
			if (r.startsWith(prefix)) {
				return r;
			}
		}
		return null;
	}

	private static String tagged(List<String> responses) {
		return ImapClient.last(responses);
	}

	private static long number(String text, String regex) {
		Matcher m = Pattern.compile(regex).matcher(text);
		assertTrue(m.find(), regex + " in " + text);
		return Long.parseLong(m.group(1));
	}

	// ------------------------------------------------------------------ connection and login

	@Test
	public void testGreetingCapabilityLogout() throws Exception {
		try (ImapClient c = connect()) {
			assertTrue(c.greeting.startsWith("* OK [CAPABILITY IMAP4rev1 IMAP4rev2 "), c.greeting);
			List<String> r = c.ok("CAPABILITY");
			String caps = find(r, "* CAPABILITY");
			for (String cap : new String[] {"IMAP4rev2", "IMAP4rev1", "STARTTLS", "AUTH=PLAIN", "SASL-IR", "LITERAL+",
					"ENABLE", "IDLE", "NAMESPACE", "UNSELECT", "UIDPLUS", "ESEARCH", "SEARCHRES", "LIST-EXTENDED",
					"LIST-STATUS", "MOVE", "SPECIAL-USE", "STATUS=SIZE", "UTF8=ACCEPT", "BINARY"}) {
				assertTrue((" " + caps + " ").contains(" " + cap + " "), cap + " in " + caps);
			}
			c.ok("NOOP");
			r = c.cmd("LOGOUT");
			assertEquals("* BYE Logging out", r.get(0));
			assertTrue(tagged(r).contains("OK LOGOUT"));
			assertNull(c.readLine(), "closed after LOGOUT");
		}
	}

	@Test
	public void testLoginFailures() throws Exception {
		try (ImapClient c = connect()) {
			assertTrue(tagged(c.cmd("LOGIN tony wrong")).contains("NO [AUTHENTICATIONFAILED]"));
			assertTrue(tagged(c.cmd("LOGIN nobody secret")).contains("NO [AUTHENTICATIONFAILED]"),
					"unknown users get the same reply");
			assertTrue(tagged(c.cmd("SELECT INBOX")).contains("BAD"), "not valid before login");
			List<String> r = c.cmd("LOGIN tony wrong2");
			assertTrue(tagged(r).contains("NO [AUTHENTICATIONFAILED]"), r.toString());
			assertEquals("* BYE Too many failed logins", c.readLine());
			assertNull(c.readLine(), "closed after 3 failures");
		}
		try (ImapClient c = connect()) {
			assertTrue(tagged(c.cmd("LOGIN nopop secret")).contains("NO [AUTHORIZATIONFAILED]"), "needs READ");
			List<String> r = c.ok("LOGIN hashed hashedpw");
			assertTrue(tagged(r).contains("[CAPABILITY IMAP4rev1"), "capabilities after login");
			assertFalse(tagged(r).contains("STARTTLS"));
			assertTrue(tagged(c.cmd("LOGIN tony secret")).contains("BAD"), "already logged in");
		}
	}

	@Test
	public void testAuthenticatePlain() throws Exception {
		String ir = Base64.getEncoder().encodeToString(bytes("\0tony\0secret"));
		try (ImapClient c = connect()) {
			c.ok("AUTHENTICATE PLAIN " + ir);
		}
		try (ImapClient c = connect()) {
			c.send("a1 AUTHENTICATE PLAIN");
			assertEquals("+ ", c.readLine());
			c.send("*");
			assertTrue(c.readLine().startsWith("a1 BAD"), "cancelled");
			c.send("a2 AUTHENTICATE PLAIN");
			assertEquals("+ ", c.readLine());
			// a decomposed user name matches after SASLprep (NFC)
			c.send(Base64.getEncoder().encodeToString(bytes("\0jo\u0308se\0p\u00e4ssw\u00f6rd")));
			String reply = c.readLine();
			assertTrue(reply.startsWith("a2 NO [AUTHENTICATIONFAILED]"), "j\u00f6se is a different user: " + reply);
			c.send("a3 AUTHENTICATE PLAIN " + Base64.getEncoder().encodeToString(bytes("\0jo\u0308se\u0301\0pa\u0308sswo\u0308rd")));
			reply = c.readLine();
			assertTrue(reply.startsWith("a3 OK"), "decomposed forms match: " + reply);
		}
		try (ImapClient c = connect()) {
			assertTrue(tagged(c.cmd("AUTHENTICATE CRAM-MD5")).contains("NO"));
		}
	}

	@Test
	public void testStartTlsAndRequireTls() throws Exception {
		server.setRequireTls(true);
		try (ImapClient c = connect()) {
			assertTrue(c.greeting.contains("LOGINDISABLED"), c.greeting);
			assertFalse(c.greeting.contains("AUTH=PLAIN"), c.greeting);
			assertTrue(tagged(c.cmd("LOGIN tony secret")).contains("NO [PRIVACYREQUIRED]"));
			c.send("s1 STARTTLS");
			assertTrue(c.readLine().startsWith("s1 OK"));
			c.startTls();
			String caps = find(c.ok("CAPABILITY"), "* CAPABILITY");
			assertFalse(caps.contains("STARTTLS"), caps);
			assertFalse(caps.contains("LOGINDISABLED"), caps);
			assertTrue(caps.contains("AUTH=PLAIN"), caps);
			c.login("tony", "secret");
			c.ok("SELECT INBOX");
			assertTrue(tagged(c.cmd("STARTTLS")).contains("BAD"), "only before login");
		} finally {
			server.setRequireTls(false);
		}
	}

	@Test
	public void testAutologout() throws Exception {
		int old = server.getAutologout();
		server.setAutologout(1500);
		try (ImapClient c = connect()) {
			long start = System.currentTimeMillis();
			assertEquals("* BYE Autologout; idle for too long", c.readLine());
			assertTrue(System.currentTimeMillis() - start >= 1000);
			assertNull(c.readLine());
		} finally {
			server.setAutologout(old);
		}
	}

	// ------------------------------------------------------------------ mailboxes

	@Test
	public void testDefaultMailboxesAndList() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			List<String> r = c.ok("LIST \"\" \"*\"");
			assertEquals("* LIST (\\Noinferiors) \"/\" INBOX", r.get(0));
			assertTrue(r.contains("* LIST (\\HasNoChildren \\Sent) \"/\" Sent"), r.toString());
			assertTrue(r.contains("* LIST (\\HasNoChildren \\Trash) \"/\" Trash"), r.toString());
			assertTrue(r.contains("* LIST (\\HasNoChildren \\Drafts) \"/\" Drafts"), r.toString());
			assertEquals(7, r.size(), "INBOX, 5 defaults and the tagged response: " + r);
			r = c.ok("LIST \"\" \"\"");
			assertEquals("* LIST (\\Noselect) \"/\" \"\"", r.get(0));
			r = c.ok("LIST (SPECIAL-USE) \"\" \"*\" RETURN (SUBSCRIBED)");
			assertEquals(6, r.size(), r.toString());
			assertTrue(r.contains("* LIST (\\HasNoChildren \\Junk \\Subscribed) \"/\" Junk"), r.toString());
			r = c.ok("LIST \"\" \"S%\" RETURN (STATUS (MESSAGES UIDNEXT))");
			assertEquals("* LIST (\\HasNoChildren \\Sent) \"/\" Sent", r.get(0));
			assertEquals("* STATUS Sent (MESSAGES 0 UIDNEXT 1)", r.get(1));
			r = c.ok("LSUB \"\" \"*\"");
			assertTrue(r.contains("* LSUB () \"/\" INBOX"), r.toString());
			r = c.ok("NAMESPACE");
			assertEquals("* NAMESPACE ((\"\" \"/\")) NIL NIL", r.get(0));
			r = c.ok("LIST \"\" \"inbox\"");
			assertEquals("* LIST (\\Noinferiors) \"/\" INBOX", r.get(0), "INBOX is case-insensitive");
		}
	}

	@Test
	public void testCreateDeleteRename() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			c.ok("CREATE Work/Projects/2026");
			List<String> r = c.ok("LIST \"\" \"Work*\"");
			assertEquals("* LIST (\\HasChildren) \"/\" Work", r.get(0));
			assertEquals("* LIST (\\HasChildren) \"/\" Work/Projects", r.get(1));
			assertEquals("* LIST (\\HasNoChildren) \"/\" Work/Projects/2026", r.get(2));
			r = c.ok("LIST \"Work/\" \"%\"");
			assertEquals(2, r.size(), r.toString());
			assertTrue(tagged(c.cmd("CREATE Work")).contains("NO [ALREADYEXISTS]"));
			c.ok("CREATE work"); // names are case-sensitive
			assertTrue(tagged(c.cmd("CREATE INBOX")).contains("NO [ALREADYEXISTS]"));
			assertTrue(tagged(c.cmd("CREATE INBOX/x")).contains("NO"));
			assertTrue(tagged(c.cmd("CREATE \"a//b\"")).contains("NO"));

			// a mailbox with children becomes \Noselect
			c.ok("DELETE Work/Projects");
			r = c.ok("LIST \"\" \"Work/*\"");
			assertEquals("* LIST (\\Noselect \\HasChildren) \"/\" Work/Projects", r.get(0));
			assertTrue(tagged(c.cmd("SELECT Work/Projects")).contains("NO"));
			assertTrue(tagged(c.cmd("DELETE Work/Projects")).contains("NO [HASCHILDREN]"));
			c.ok("CREATE Work/Projects"); // selectable again

			c.ok("RENAME Work Play");
			r = c.ok("LIST \"\" \"*\"");
			assertTrue(r.contains("* LIST (\\HasNoChildren) \"/\" Play/Projects/2026"), r.toString());
			assertNull(find(r, "* LIST (\\HasChildren) \"/\" Work"), r.toString());
			assertTrue(tagged(c.cmd("RENAME Play Sent")).contains("NO [ALREADYEXISTS]"));
			assertTrue(tagged(c.cmd("RENAME Nothing Else")).contains("NO [NONEXISTENT]"));
			c.ok("RENAME Play/Projects/2026 New/Deep/Box");
			assertNotNull(find(c.ok("LIST \"\" New/Deep/Box"), "* LIST (\\HasNoChildren) \"/\" New/Deep/Box"));

			// renaming INBOX moves its messages
			c.append("INBOX", null, bytes(SIMPLE));
			c.ok("RENAME INBOX Old");
			assertTrue(find(c.ok("STATUS INBOX (MESSAGES)"), "* STATUS").contains("MESSAGES 0"));
			assertTrue(find(c.ok("STATUS Old (MESSAGES)"), "* STATUS").contains("MESSAGES 1"));

			assertTrue(tagged(c.cmd("DELETE INBOX")).contains("NO"));
			c.ok("DELETE New/Deep/Box");
			assertTrue(tagged(c.cmd("DELETE New/Deep/Box")).contains("NO [NONEXISTENT]"));
			c.ok("SUBSCRIBE Some/Name");
			assertNotNull(find(c.ok("LSUB \"\" \"Some/*\""), "* LSUB (\\Noselect) \"/\" Some/Name"));
			c.ok("UNSUBSCRIBE Some/Name");
			c.ok("UNSUBSCRIBE Some/Name"); // not an error in IMAP4rev2
			assertEquals(1, c.ok("LSUB \"\" \"Some/*\"").size());
		}
	}

	// ------------------------------------------------------------------ messages

	@Test
	public void testAppendFetch() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			List<String> r = c.ok("SELECT INBOX");
			assertTrue(r.contains("* 0 EXISTS"), r.toString());
			assertTrue(r.contains("* 0 RECENT"), "IMAP4rev1 session: " + r);
			long validity = number(find(r, "* OK [UIDVALIDITY"), "UIDVALIDITY (\\d+)");
			assertTrue(tagged(r).contains("[READ-WRITE]"));

			r = c.append("INBOX", "(\\Flagged $Work) \" 2-Oct-2026 10:00:00 +0000\"", bytes(SIMPLE));
			assertTrue(r.contains("* 1 EXISTS"), r.toString());
			assertTrue(tagged(r).contains("[APPENDUID " + validity + " 1]"), tagged(r));

			r = c.ok("FETCH 1 (UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE)");
			String f = r.get(0);
			assertTrue(f.startsWith("* 1 FETCH (UID 1 FLAGS (\\Flagged $Work) INTERNALDATE \""), f);
			String date = f.substring(f.indexOf("INTERNALDATE \"") + 14, f.indexOf("\" RFC822.SIZE"));
			assertEquals(ZonedDateTime.parse("2026-10-02T10:00:00Z").toInstant(),
					ZonedDateTime.parse(date, DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.US)).toInstant());
			assertTrue(f.contains("RFC822.SIZE " + SIMPLE.length()), f);
			assertTrue(f.contains("ENVELOPE (\"Fri, 02 Oct 2026 15:00:00 -0400\" \"Test 1\" ((\"Fred Foo\" NIL \"fred\" \"example.com\")) "
					+ "((\"Fred Foo\" NIL \"fred\" \"example.com\")) ((\"Fred Foo\" NIL \"fred\" \"example.com\")) "
					+ "((\"Tony\" NIL \"tony\" \"bringardner.us\")) NIL NIL NIL \"<m1@example.com>\")"), f);
			assertTrue(f.endsWith("BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"us-ascii\") NIL NIL \"7BIT\" 14 2 NIL NIL NIL NIL))"), f);

			// PEEK doesn't set \Seen
			r = c.ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS (Subject From)] BODY.PEEK[TEXT]<1.3>)");
			assertEquals("* 1 FETCH (BODY[HEADER.FIELDS (SUBJECT FROM)] {54}\r\nFrom: Fred Foo <fred@example.com>\r\n"
					+ "Subject: Test 1\r\n\r\n BODY[TEXT]<1> {3}\r\nell)", r.get(0));
			r = c.ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS.NOT (FROM TO DATE MESSAGE-ID)])");
			assertEquals("* 1 FETCH (BODY[HEADER.FIELDS.NOT (FROM TO DATE MESSAGE-ID)] {19}\r\nSubject: Test 1\r\n\r\n)", r.get(0));
			assertTrue(c.ok("FETCH 1 FLAGS").get(0).contains("FLAGS (\\Flagged $Work)"));

			// BODY[] sets \Seen and reports the new flags
			r = c.ok("FETCH 1 BODY[]");
			assertEquals("* 1 FETCH (BODY[] {" + SIMPLE.length() + "}\r\n" + SIMPLE + " FLAGS (\\Flagged $Work \\Seen))", r.get(0));
			r = c.ok("UID FETCH 1 (BODY[1] RFC822.HEADER)");
			assertTrue(r.get(0).startsWith("* 1 FETCH (UID 1 BODY[1] {14}\r\nHello\r\nWorld\r\n RFC822.HEADER {"), r.get(0));
			r = c.ok("FETCH 1 (BODY[]<100.1000>)");
			assertEquals("* 1 FETCH (BODY[]<100> {" + (SIMPLE.length() - 100) + "}\r\n" + SIMPLE.substring(100) + ")", r.get(0));
			r = c.ok("FETCH 1 FAST");
			assertTrue(r.get(0).matches("\\* 1 FETCH \\(FLAGS \\(.*\\) INTERNALDATE \".*\" RFC822.SIZE \\d+\\)"), r.get(0));

			assertTrue(tagged(c.cmd("FETCH 2 FLAGS")).contains("BAD"), "no message 2");
			assertEquals(1, c.ok("UID FETCH 5:* FLAGS").size() - 1, "5:* includes the last message");
			assertEquals(0, c.ok("UID FETCH 5 FLAGS").size() - 1);
			assertTrue(tagged(c.cmd("FETCH 1 (BODY[HEADER.FIELDS ()])")).contains("BAD"));
			assertTrue(tagged(c.cmd("FETCH 1 BODY[1.2.3]")).contains("OK"));
		}
	}

	@Test
	public void testMultipartSectionsAndBinary() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			c.ok("ENABLE IMAP4rev2");
			c.ok("SELECT INBOX");
			c.append("INBOX", null, bytes(MULTIPART));
			List<String> r = c.ok("FETCH 1 BODYSTRUCTURE");
			String bs = r.get(0);
			assertTrue(bs.startsWith("* 1 FETCH (BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"QUOTED-PRINTABLE\" 15 1 NIL NIL NIL NIL)"
					+ "(\"APPLICATION\" \"OCTET-STREAM\" (\"NAME\" \"data.bin\") NIL NIL \"BASE64\" 8 NIL (\"ATTACHMENT\" (\"FILENAME\" \"data.bin\")) NIL NIL)"
					+ "(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" 49 (NIL \"inner\" "), bs);
			assertTrue(bs.endsWith(" \"MIXED\" (\"BOUNDARY\" \"b1\") NIL NIL NIL))"), bs);
			r = c.ok("FETCH 1 BODY");
			assertTrue(r.get(0).endsWith(" \"MIXED\"))"), "BODY has no extension data: " + r.get(0));

			r = c.ok("FETCH 1 (BODY.PEEK[1] BINARY.PEEK[1] BINARY.SIZE[1] BINARY.SIZE[2])");
			assertEquals("* 1 FETCH (BODY[1] {15}\r\nH=C3=A4llo Welt BINARY[1] {11}\r\nH\u00e4llo Welt"
					+ " BINARY.SIZE[1] 11 BINARY.SIZE[2] 5)", r.get(0), "the CRLF before a boundary belongs to the boundary");
			r = c.ok("FETCH 1 BINARY.PEEK[2]");
			assertEquals("* 1 FETCH (BINARY[2] ~{5}\r\n\0\1\2\3\4)", r.get(0), "literal8 for NUL");
			r = c.ok("FETCH 1 (BODY.PEEK[2.MIME] BODY.PEEK[3.HEADER] BODY.PEEK[3.TEXT] BODY.PEEK[3.1])");
			assertTrue(r.get(0).contains("BODY[2.MIME] {"), r.get(0));
			assertTrue(r.get(0).contains("Content-Disposition: attachment; filename=\"data.bin\"\r\nContent-Transfer-Encoding: base64\r\n\r\n BODY[3.HEADER]"), r.get(0));
			assertTrue(r.get(0).contains("BODY[3.HEADER] {39}\r\nSubject: inner\r\nFrom: b@example.com\r\n\r\n"), r.get(0));
			assertTrue(r.get(0).contains("BODY[3.TEXT] {10}\r\ninner body "), r.get(0));
			assertTrue(r.get(0).endsWith("BODY[3.1] {10}\r\ninner body)"), r.get(0));
			assertTrue(tagged(c.cmd("FETCH 1 BINARY[1.MIME]")).contains("BAD"));
		}
	}

	@Test
	public void testSearch() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			c.ok("SELECT INBOX");
			c.append("INBOX", "(\\Seen)", bytes(SIMPLE));
			c.append("INBOX", null, bytes(MULTIPART));
			c.append("INBOX", "(\\Deleted)", bytes(SIMPLE.replace("Test 1", "Other").replace("Hello", "Bye")));
			assertEquals("* SEARCH 1 3", c.ok("SEARCH FROM fred").get(0));
			assertEquals("* SEARCH 2 3", c.ok("SEARCH UNSEEN").get(0));
			assertEquals("* SEARCH 2", c.ok("SEARCH CHARSET UTF-8 BODY \"hällo\"").get(0), "decoded quoted-printable");
			assertEquals("* SEARCH 1", c.ok("SEARCH BODY world NOT DELETED").get(0));
			assertEquals("* SEARCH 1 2", c.ok("SEARCH OR SUBJECT test SUBJECT parts").get(0));
			assertEquals("* SEARCH 3", c.ok("SEARCH HEADER Subject other").get(0));
			assertEquals("* SEARCH 2", c.ok("SEARCH TEXT \"inner body\"").get(0));
			assertEquals("* SEARCH 1 3", c.ok("SEARCH SINCE 1-Jan-2020 SENTBEFORE 1-Jan-2030").get(0), "message 2 has no Date");
			assertEquals("* SEARCH 1 3", c.ok("SEARCH SENTON 2-Oct-2026").get(0));
			assertEquals("* SEARCH 2 3", c.ok("SEARCH 2:*").get(0));
			assertEquals("* SEARCH 2 3", c.ok("UID SEARCH UID 2:3").get(0));
			assertEquals("* SEARCH", c.ok("SEARCH NEW").get(0));
			assertEquals("* SEARCH 1 2 3", c.ok("SEARCH (OLD ALL)").get(0));
			assertTrue(tagged(c.cmd("SEARCH CHARSET KOI8-R ALL")).contains("NO [BADCHARSET"));
			assertTrue(tagged(c.cmd("SEARCH BOGUS")).contains("BAD"));

			// ESEARCH with RETURN, and SAVE for "$"
			assertEquals("* ESEARCH (TAG \"t" + (17) + "\") MIN 1 MAX 3 COUNT 2 ALL 1,3",
					c.ok("SEARCH RETURN (MIN MAX COUNT ALL) FROM fred").get(0).replaceAll("TAG \"t\\d+\"", "TAG \"t17\""));
			List<String> r = c.ok("UID SEARCH RETURN (SAVE) UNSEEN");
			assertEquals(1, r.size(), "SAVE alone: no ESEARCH response");
			r = c.ok("FETCH $ (UID)");
			assertEquals("* 2 FETCH (UID 2)", r.get(0));
			assertEquals("* 3 FETCH (UID 3)", r.get(1));
			assertEquals("* SEARCH 3", c.ok("SEARCH $ DELETED").get(0));

			// IMAP4rev2: always ESEARCH
			c.ok("ENABLE IMAP4rev2");
			r = c.ok("SEARCH SUBJECT nothing-matches");
			assertTrue(r.get(0).matches("\\* ESEARCH \\(TAG \"t\\d+\"\\)"), r.get(0));
			r = c.ok("UID SEARCH ALL");
			assertTrue(r.get(0).matches("\\* ESEARCH \\(TAG \"t\\d+\"\\) UID ALL 1:3"), r.get(0));
			r = c.ok("SEARCH RETURN (COUNT) DELETED");
			assertTrue(r.get(0).endsWith(" COUNT 1"), r.get(0));
		}
	}

	@Test
	public void testStoreExpungeClose() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			c.ok("SELECT INBOX");
			for (int i = 0; i < 5; i++) {
				c.append("INBOX", null, bytes(SIMPLE));
			}
			List<String> r = c.ok("STORE 1:2 +FLAGS (\\Deleted \\Seen)");
			assertEquals("* 1 FETCH (FLAGS (\\Deleted \\Seen))", r.get(0));
			r = c.ok("UID STORE 2 -FLAGS (\\Seen)");
			assertEquals("* 2 FETCH (UID 2 FLAGS (\\Deleted))", r.get(0));
			assertEquals(1, c.ok("STORE 3 +FLAGS.SILENT (\\Deleted $Label)").size());
			r = c.ok("STORE 4 FLAGS ($A $B)");
			assertEquals("* 4 FETCH (FLAGS ($A $B))", r.get(0));
			assertTrue(tagged(c.cmd("STORE 4 +FLAGS (\\Bogus)")).contains("BAD"));
			assertTrue(tagged(c.cmd("STORE 4 +FLAGS (\\Recent)")).contains("OK"), "\\Recent is ignored");

			r = c.ok("UID EXPUNGE 2:3");
			assertEquals("* 3 EXPUNGE", r.get(0));
			assertEquals("* 2 EXPUNGE", r.get(1));
			r = c.ok("EXPUNGE");
			assertEquals("* 1 EXPUNGE", r.get(0));
			assertEquals("* SEARCH 1 2", c.ok("SEARCH ALL").get(0));
			assertEquals("* SEARCH 4 5", c.ok("UID SEARCH ALL").get(0));

			c.ok("STORE 1 +FLAGS (\\Deleted)");
			r = c.ok("CLOSE");
			assertEquals(1, r.size(), "CLOSE sends no EXPUNGE: " + r);
			assertTrue(tagged(c.cmd("FETCH 1 FLAGS")).contains("BAD"), "no mailbox selected");
			r = c.ok("EXAMINE INBOX");
			assertTrue(r.contains("* 1 EXISTS"), r.toString());
			assertTrue(tagged(r).contains("[READ-ONLY]"));
			assertTrue(r.contains("* OK [PERMANENTFLAGS ()] No permanent flags permitted"), r.toString());
			assertTrue(tagged(c.cmd("STORE 1 +FLAGS (\\Seen)")).contains("NO"));
			c.ok("FETCH 1 BODY[]");
			assertEquals("* 1 FETCH (FLAGS ())", c.ok("FETCH 1 FLAGS").get(0), "EXAMINE doesn't set \\Seen");
			c.ok("UNSELECT");
		}
	}

	@Test
	public void testCopyMove() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			c.ok("ENABLE IMAP4rev2");
			c.ok("SELECT INBOX");
			for (int i = 0; i < 3; i++) {
				c.append("INBOX", "(\\Flagged)", bytes(SIMPLE));
			}
			long trash = number(find(c.ok("STATUS Trash (UIDVALIDITY)"), "* STATUS"), "UIDVALIDITY (\\d+)");
			List<String> r = c.ok("COPY 1:2 Trash");
			assertTrue(tagged(r).contains("[COPYUID " + trash + " 1:2 1:2]"), tagged(r));
			assertTrue(tagged(c.cmd("COPY 1 Nowhere")).contains("NO [TRYCREATE]"));
			r = c.ok("UID MOVE 2:3 Trash");
			assertEquals("* OK [COPYUID " + trash + " 2:3 3:4] Moved", r.get(0));
			assertEquals("* 3 EXPUNGE", r.get(1));
			assertEquals("* 2 EXPUNGE", r.get(2));
			assertEquals("* 1 EXISTS", r.get(3));
			assertTrue(find(c.ok("STATUS Trash (MESSAGES SIZE)"), "* STATUS")
					.equals("* STATUS Trash (MESSAGES 4 SIZE " + 4 * SIMPLE.length() + ")"));
			c.ok("SELECT Trash");
			r = c.ok("FETCH 1:* (UID FLAGS)");
			assertEquals(4, r.size() - 1);
			assertEquals("* 4 FETCH (UID 4 FLAGS (\\Flagged))", r.get(3), "flags are copied");
		}
	}

	// ------------------------------------------------------------------ sessions

	@Test
	public void testTwoSessions() throws Exception {
		reset("tony");
		try (ImapClient a = login("tony", "secret"); ImapClient b = login("tony", "secret")) {
			a.ok("SELECT INBOX");
			b.ok("ENABLE IMAP4rev2");
			b.ok("SELECT INBOX");
			for (int i = 0; i < 3; i++) {
				a.append("INBOX", null, bytes(SIMPLE));
			}
			List<String> r = b.ok("NOOP");
			assertTrue(r.contains("* 3 EXISTS"), r.toString());

			a.ok("STORE 2 +FLAGS.SILENT (\\Deleted)");
			r = b.ok("NOOP");
			assertEquals("* 2 FETCH (UID 2 FLAGS (\\Deleted))", r.get(0), "unsolicited FETCH includes UID");
			a.ok("EXPUNGE");

			// no EXPUNGE during FETCH, STORE or SEARCH
			r = b.cmd("FETCH 1:3 (UID)");
			assertEquals("* 1 FETCH (UID 1)", r.get(0));
			assertEquals("* 3 FETCH (UID 3)", r.get(1), "message 3 keeps its number: " + r);
			assertTrue(tagged(r).contains("NO [EXPUNGEISSUED]"), r.toString());
			r = b.ok("SEARCH ALL");
			assertNull(find(r, "* 2 EXPUNGE"));
			r = b.ok("NOOP");
			assertEquals("* 2 EXPUNGE", r.get(0));
			assertEquals("* 2 EXISTS", r.get(1));
			assertEquals("* ESEARCH (TAG \"x\") UID ALL 1,3",
					find(b.ok("UID SEARCH ALL"), "* ESEARCH").replaceAll("TAG \"t\\d+\"", "TAG \"x\""));

			// another session deletes the selected mailbox
			b.ok("CREATE Temp");
			b.ok("SELECT Temp");
			a.ok("DELETE Temp");
			r = b.ok("NOOP");
			assertTrue(r.contains("* OK [CLOSED] The mailbox no longer exists"), r.toString());
			assertTrue(tagged(b.cmd("FETCH 1 FLAGS")).contains("BAD"));
		}
	}

	@Test
	public void testIdle() throws Exception {
		reset("tony");
		try (ImapClient a = login("tony", "secret"); ImapClient b = login("tony", "secret")) {
			a.ok("SELECT INBOX");
			a.send("i1 IDLE");
			assertEquals("+ idling", a.readLine());
			b.append("INBOX", null, bytes(SIMPLE));
			assertEquals("* 1 EXISTS", a.readLine());
			assertEquals("* 0 RECENT", a.readLine());
			b.ok("SELECT INBOX");
			b.ok("STORE 1 +FLAGS.SILENT (\\Answered)");
			assertEquals("* 1 FETCH (UID 1 FLAGS (\\Answered))", a.readLine());
			a.send("DONE");
			assertEquals("i1 OK IDLE terminated", a.readLine());
			a.ok("NOOP");
		}
	}

	// ------------------------------------------------------------------ internationalized mail

	@Test
	public void testUtf8() throws Exception {
		reset("jösé");
		try (ImapClient c = login("jösé", "pässwörd")) {
			c.ok("ENABLE IMAP4rev2");
			c.ok("CREATE \"Ünïcödé/Kid\"");
			c.ok("SELECT INBOX");
			c.append("INBOX", null, bytes(UTF8));
			List<String> r = c.ok("FETCH 1 (RFC822.SIZE ENVELOPE)");
			assertTrue(r.get(0).contains("RFC822.SIZE " + bytes(UTF8).length), r.get(0));
			assertTrue(r.get(0).contains("(\"José\" NIL \"josé\" \"exämple.com\")"), r.get(0));
			assertTrue(r.get(0).contains("\"Grüße\""), r.get(0));
			assertNotNull(find(c.ok("LIST \"\" \"Ü*\""), "* LIST (\\HasChildren) \"/\" \"Ünïcödé\""));
		}
		try (ImapClient c = login("jösé", "pässwörd")) {
			// IMAP4rev1 without UTF8=ACCEPT: modified UTF-7 names and the RFC 6858 surrogate
			List<String> r = c.ok("LIST \"\" \"*\"");
			assertTrue(r.contains("* LIST (\\HasNoChildren) \"/\" &ANw-n&AO8-c&APY-d&AOk-/Kid"), r.toString());
			c.ok("SELECT \"&ANw-n&AO8-c&APY-d&AOk-/Kid\"");
			c.ok("SELECT INBOX");
			r = c.ok("FETCH 1 (RFC822.SIZE ENVELOPE BODY.PEEK[HEADER])");
			String f = r.get(0);
			for (int i = 0; i < f.length(); i++) {
				assertTrue(f.charAt(i) < 128, "ASCII only: " + f);
			}
			assertTrue(f.contains("\"invalid\" \"internationalized-address.invalid\""), f);
			assertTrue(f.contains("\"=?UTF-8?"), f);
			long size = number(f, "RFC822.SIZE (\\d+)");
			assertTrue(size > bytes(UTF8).length, "the surrogate's size");
			assertEquals("* SEARCH 1", c.ok("SEARCH SUBJECT \"Gr\"").get(0));

			c.ok("ENABLE UTF8=ACCEPT");
			r = c.ok("FETCH 1 (RFC822.SIZE)");
			assertTrue(r.get(0).contains("RFC822.SIZE " + bytes(UTF8).length), "as stored after UTF8=ACCEPT: " + r.get(0));
			r = c.ok("LIST \"\" \"Ü*\"");
			assertNotNull(find(r, "* LIST (\\HasChildren) \"/\" \"Ünïcödé\""), r.toString());
		}
	}

	// ------------------------------------------------------------------ shared with POP3

	/** A small POP3 conversation. */
	private static final class Pop3 implements AutoCloseable {
		final Socket s;
		final InputStream in;
		final OutputStream out;

		Pop3() throws IOException {
			s = new Socket("localhost", pop3.getLocalPort());
			s.setSoTimeout(30000);
			in = new BufferedInputStream(s.getInputStream());
			out = s.getOutputStream();
			line();
		}

		String line() throws IOException {
			StringBuilder sb = new StringBuilder();
			int b;
			while ((b = in.read()) >= 0 && b != '\n') {
				if (b != '\r') {
					sb.append((char) b);
				}
			}
			return sb.toString();
		}

		String cmd(String text) throws IOException {
			out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			return line();
		}

		@Override
		public void close() throws IOException {
			s.close();
		}
	}

	@Test
	public void testSharedWithPop3() throws Exception {
		FileSource team = reset("team");
		try (ImapClient c = login("team1", "secret")) {
			c.ok("SELECT INBOX");
			c.ok("CREATE Folder");
			c.append("INBOX", null, bytes(SIMPLE));
			c.append("Folder", null, bytes(SIMPLE));

			// POP3 sees INBOX only, not the IMAP files and mailboxes
			try (Pop3 p = new Pop3()) {
				assertTrue(p.cmd("USER team2").startsWith("+OK"));
				assertTrue(p.cmd("PASS secret").startsWith("+OK"));
				assertEquals("+OK 1 " + SIMPLE.length(), p.cmd("STAT"));
				assertTrue(p.cmd("DELE 1").startsWith("+OK"));
				assertTrue(p.cmd("QUIT").startsWith("+OK"));
			}
			List<String> r = c.ok("NOOP");
			assertEquals("* 1 EXPUNGE", r.get(0), "a POP3 deletion is an expunge: " + r);

			// mail delivered to the maildrop appears in INBOX
			Maildrop.deliver(team, new java.io.ByteArrayInputStream(bytes(SIMPLE.replace("\r\n", "\n"))));
			Thread.sleep(1100); // refresh is throttled to once a second
			r = c.ok("NOOP");
			assertTrue(r.contains("* 1 EXISTS"), r.toString());
			r = c.ok("FETCH 1 (UID RFC822.SIZE BODY[TEXT])");
			assertTrue(r.get(0).startsWith("* 1 FETCH (UID 2 RFC822.SIZE " + SIMPLE.length() + " BODY[TEXT] {14}\r\nHello\r\nWorld\r\n"),
					"stored with CRLF: " + r.get(0));
		}
		try (Pop3 p = new Pop3()) {
			p.cmd("USER team1");
			p.cmd("PASS secret");
			assertEquals("+OK 1 " + SIMPLE.length(), p.cmd("STAT"));
			p.cmd("QUIT");
		}
	}

	@Test
	public void testIndexPersistence() throws Exception {
		FileSource dir = reset("tony");
		long validity;
		try (ImapClient c = login("tony", "secret")) {
			validity = number(find(c.ok("SELECT INBOX"), "* OK [UIDVALIDITY"), "UIDVALIDITY (\\d+)");
			c.append("INBOX", "(\\Flagged)", bytes(SIMPLE));
			c.append("INBOX", null, bytes(SIMPLE));
			c.ok("STORE 1 FLAGS (\\Seen $Keep)");
			c.ok("STORE 2 +FLAGS (\\Deleted)");
			c.ok("EXPUNGE");
		}
		String index = new String(dir.getChild(".imap-index").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(index.contains("\\Seen $Keep"), index);
		try (ImapClient c = login("tony", "secret")) {
			List<String> r = c.ok("SELECT INBOX");
			assertEquals(validity, number(find(r, "* OK [UIDVALIDITY"), "UIDVALIDITY (\\d+)"));
			assertTrue(r.contains("* OK [UIDNEXT 3] Predicted next UID"), r.toString());
			assertEquals("* 1 FETCH (UID 1 FLAGS (\\Seen $Keep))", c.ok("FETCH 1 (UID FLAGS)").get(0));
			List<String> a = c.append("INBOX", null, bytes(SIMPLE));
			assertTrue(tagged(a).contains("[APPENDUID " + validity + " 3]"), "UIDs are never reused: " + a);
		}
	}

	// ------------------------------------------------------------------ literals, limits and errors

	@Test
	public void testLiteralsAndErrors() throws Exception {
		reset("tony");
		try (ImapClient c = login("tony", "secret")) {
			// LITERAL+: no continuation
			byte[] m = bytes(SIMPLE);
			c.sendBytes(bytes("p1 APPEND INBOX {" + m.length + "+}\r\n"));
			c.sendBytes(m);
			c.send("");
			assertTrue(c.readLine().startsWith("p1 OK [APPENDUID"));
			c.send("p2 LOGIN {4}");
			assertEquals("+ Ready for literal data", c.readLine());
			c.send("tony {6+}");
			c.send("secret");
			assertTrue(c.readLine().startsWith("p2 BAD"), "LOGIN parsed, but not valid in this state");

			long old = server.getAppendLimit();
			server.setAppendLimit(10);
			try (ImapClient d = login("tony", "secret")) {
				d.send("p3 APPEND INBOX {11}");
				assertEquals("p3 NO [TOOBIG] Message too large", d.readLine());
				d.ok("NOOP");
			} finally {
				server.setAppendLimit(old);
			}
			assertTrue(tagged(c.cmd("FOO")).contains("BAD Unknown command"));
			assertTrue(tagged(c.cmd("SELECT")).contains("BAD"));
			assertTrue(tagged(c.cmd("SELECT INBOX extra")).contains("BAD"));
			assertTrue(tagged(c.cmd("LIST \"\" (\"unclosed\"")).contains("BAD"));
			c.send("+bad tag");
			assertTrue(c.readLine().startsWith("* BAD"));
			c.send("x".repeat(70000));
			assertEquals("* BAD Command line too long", c.readLine());
			assertTrue(tagged(c.cmd("APPEND Nowhere {1+}\r\nx")).contains("NO [TRYCREATE]"));
			assertTrue(tagged(c.cmd("STATUS INBOX (BOGUS)")).contains("BAD"));
			c.ok("NOOP");
		}
	}

	@Test
	public void testReadOnlyUser() throws Exception {
		reset("reader");
		try (ImapClient c = login("reader", "secret")) {
			List<String> r = c.ok("SELECT INBOX");
			assertTrue(tagged(r).contains("[READ-ONLY]"), "no WRITE permission: " + r);
			assertTrue(tagged(c.append("INBOX", null, bytes(SIMPLE))).contains("NO [NOPERM]"));
			assertTrue(tagged(c.cmd("CREATE X")).contains("NO [NOPERM]"));
			assertTrue(tagged(c.cmd("DELETE Sent")).contains("NO [NOPERM]"));
		}
	}

	// ------------------------------------------------------------------ large messages

	/** A message larger than the test heap (64 MB) is appended and fetched by streaming. */
	@Test
	public void testLargeMessage() throws Exception {
		reset("tony");
		Random random = new Random(42);
		byte[] chunk = new byte[57 * 1024];
		random.nextBytes(chunk);
		StringBuilder lines = new StringBuilder();
		for (int i = 0; i < chunk.length; i += 57) {
			lines.append(Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(chunk, i, i + 57))).append("\r\n");
		}
		byte[] block = bytes(lines.toString());
		int blocks = 900; // about 70 MB
		byte[] head = bytes("From: big@example.com\r\nSubject: big\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed; boundary=XX\r\n\r\n--XX\r\nContent-Type: text/plain\r\n\r\nsee attached\r\n"
				+ "--XX\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\n\r\n");
		byte[] tail = bytes("--XX--\r\n");
		long total = head.length + (long) block.length * blocks + tail.length;
		MessageDigest sent = MessageDigest.getInstance("SHA-256");
		try (ImapClient c = login("tony", "secret")) {
			c.ok("SELECT INBOX");
			c.send("big APPEND INBOX {" + total + "}");
			assertTrue(c.readLine().startsWith("+"));
			c.sendBytes(head);
			sent.update(head);
			for (int i = 0; i < blocks; i++) {
				c.sendBytes(block);
				sent.update(block);
			}
			c.sendBytes(tail);
			sent.update(tail);
			c.send("");
			List<String> r = c.until("big");
			assertTrue(tagged(r).startsWith("big OK [APPENDUID"), r.toString());

			r = c.ok("FETCH 1 (RFC822.SIZE BINARY.SIZE[2] BODYSTRUCTURE)");
			assertTrue(r.get(0).contains("RFC822.SIZE " + total), r.get(0));
			assertTrue(r.get(0).contains("BINARY.SIZE[2] " + (long) chunk.length * blocks), r.get(0));
			MessageDigest got = MessageDigest.getInstance("SHA-256");
			long[] n = c.fetchDigest("FETCH 1 BODY.PEEK[]", got);
			assertEquals(total, n[0]);
			assertTrue(MessageDigest.isEqual(sent.digest(), got.digest()), "content survives the round trip");
			assertEquals("* SEARCH 1", c.ok("SEARCH BODY \"see attached\"").get(0));
		}
	}

	/** The shared MaxLoginAttempts setting */
	@Test
	public void testMaxLoginAttempts() throws Exception {
		server.setMaxLoginAttempts(2);
		try (ImapClient c = connect()) {
			assertTrue(tagged(c.cmd("LOGIN tony wrong")).contains("NO [AUTHENTICATIONFAILED]"));
			assertTrue(tagged(c.cmd("LOGIN tony wrong2")).contains("NO [AUTHENTICATIONFAILED]"));
			assertEquals("* BYE Too many failed logins", c.readLine());
			assertNull(c.readLine(), "closed after 2 failures");
		} finally {
			server.setMaxLoginAttempts(3);
		}
	}
}
