package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.imap.client.BodyPart;
import us.bringardner.parley.imap.client.Envelope;
import us.bringardner.parley.imap.client.ImapCli;
import us.bringardner.parley.imap.client.ImapClient;
import us.bringardner.parley.imap.client.ImapClientConfig;
import us.bringardner.parley.imap.client.ImapClientConfig.Security;
import us.bringardner.parley.imap.client.ImapException;
import us.bringardner.parley.imap.client.ImapListener;
import us.bringardner.parley.imap.client.MailboxInfo;
import us.bringardner.parley.imap.client.MailboxStatus;
import us.bringardner.parley.imap.client.MessageSummary;
import us.bringardner.parley.imap.client.SearchCriteria;
import us.bringardner.parley.imap.client.SelectedMailbox;
import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.pop3.test.TestPop3Server;

/**
 * The IMAP client library and command-line program against an in-process
 * {@link ImapServer}: IMAP4rev2 and IMAP4rev1, STARTTLS and TLS, IDLE events,
 * MIME parts and a message larger than the test heap.
 */
public class TestImapClient {

	private static final String KEYSTORE = "target/imapclientkeystore.p12";

	private static ImapServer server;
	private static ImapServer tlsServer;
	private static FileSource root;
	private static int port;
	private static final AtomicInteger names = new AtomicInteger();

	@BeforeAll
	public static void startServer() throws Exception {
		TestPop3Server.makeTestKeystore(new File(KEYSTORE));
		System.setProperty("ImapServer.KeyStoreName", KEYSTORE);
		System.setProperty("ImapServer.KeyStorePassword", TestPop3Server.KEYSTORE_PASSWORD);
		System.setProperty("ImapServer.KeyStoreType", "PKCS12");
		System.setProperty("ImapServer." + FileBasedAcl.PROP_FILE_NAME, "Pop3TestAcl.txt");
		System.setProperty("ImapServer." + IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getName());
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("imapclienttest");
		server = new ImapServer(0, ImapServer.IMAP_NAME, false);
		server.setMaildropRoot(root);
		server.setLoginFailureDelay(0);
		server.getLogger().setLevel(Level.ERROR);
		server.startAndWait(10000);
		port = server.getLocalPort();
	}

	@AfterAll
	public static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
		if (tlsServer != null) {
			tlsServer.stop();
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

	private static ImapClientConfig config() {
		return new ImapClientConfig("localhost", Security.NONE).setPort(port).setReadTimeout(30000);
	}

	private static ImapClient login(ImapClientConfig config, String user, String password) throws IOException {
		ImapClient c = new ImapClient(config);
		c.connect();
		c.login(user, password);
		return c;
	}

	private static ImapClient login() throws IOException {
		return login(config(), "tony", "secret");
	}

	/** A new mailbox name for each test (tests share tony's maildrop). */
	private static String newMailbox(String base) {
		return base + names.incrementAndGet();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static long append(ImapClient c, String mailbox, String message, String... flags) throws IOException {
		byte[] b = bytes(message);
		return c.append(mailbox, new ByteArrayInputStream(b), b.length, Arrays.asList(flags), null, null);
	}

	// ------------------------------------------------------------------ connection

	@Test
	public void testLoginRev2() throws Exception {
		try (ImapClient c = login()) {
			assertEquals(ImapClient.State.AUTHENTICATED, c.getState());
			assertTrue(c.isRev2());
			assertTrue(c.isUtf8());
			assertTrue(c.hasCapability("IDLE"));
			assertFalse(c.hasCapability("AUTH=PLAIN"), "capabilities are refreshed after login");
			c.logout();
			assertEquals(ImapClient.State.DISCONNECTED, c.getState());
		}
	}

	@Test
	public void testBadPassword() throws Exception {
		try (ImapClient c = new ImapClient(config())) {
			c.connect();
			ImapException e = assertThrows(ImapException.class, () -> c.login("tony", "wrong"));
			assertEquals("NO", e.getStatus());
			assertEquals(ImapClient.State.NOT_AUTHENTICATED, c.getState());
			c.login("tony", "secret");
			assertEquals(ImapClient.State.AUTHENTICATED, c.getState());
		}
	}

	@Test
	public void testStartTls() throws Exception {
		List<String> trace = Collections.synchronizedList(new ArrayList<>());
		ImapClientConfig cfg = config().setSecurity(Security.STARTTLS).setTrustAllCertificates(true);
		try (ImapClient c = new ImapClient(cfg)) {
			c.setTrace(trace::add);
			c.connect();
			assertFalse(c.hasCapability("STARTTLS"), "capabilities after TLS");
			c.login("tony", "secret");
			assertTrue(c.listAll().stream().anyMatch(MailboxInfo::isInbox));
		}
		assertTrue(trace.stream().anyMatch(s -> s.endsWith("STARTTLS")), trace.toString());
		assertTrue(trace.stream().noneMatch(s -> s.contains("secret")), "the password isn't traced");
	}

	@Test
	public void testImplicitTls() throws Exception {
		synchronized (TestImapClient.class) {
			if (tlsServer == null) {
				tlsServer = new ImapServer(0, ImapServer.IMAP_NAME, true);
				tlsServer.setMaildropRoot(root);
				tlsServer.setLoginFailureDelay(0);
				tlsServer.getLogger().setLevel(Level.ERROR);
				tlsServer.startAndWait(10000);
			}
		}
		ImapClientConfig cfg = new ImapClientConfig("localhost", Security.TLS).setPort(tlsServer.getLocalPort())
				.setTrustAllCertificates(true);
		try (ImapClient c = login(cfg, "tony", "secret")) {
			assertNotNull(c.status("INBOX"));
		}
	}

	// ------------------------------------------------------------------ mailboxes

	@Test
	public void testMailboxes() throws Exception {
		String box = newMailbox("Projects");
		try (ImapClient c = login()) {
			c.create(box + "/Ünïcødé");
			List<MailboxInfo> list = c.list("", box + "*");
			assertEquals(2, list.size(), list.toString());
			MailboxInfo kid = list.stream().filter(m -> m.getName().endsWith("Ünïcødé")).findFirst().get();
			assertEquals("/", kid.getDelimiter());
			assertEquals("Ünïcødé", kid.getShortName());
			assertEquals(box, kid.getParentName());
			c.subscribe(box);
			assertTrue(c.listSubscribed().stream().anyMatch(m -> m.getName().equals(box)));
			c.rename(box + "/Ünïcødé", box + "/Renamed");
			MailboxStatus s = c.status(box + "/Renamed");
			assertEquals(0, s.getMessages());
			assertTrue(s.getUidValidity() > 0);
			c.delete(box + "/Renamed");
			assertEquals(1, c.list("", box + "*").size());
			ImapException e = assertThrows(ImapException.class, () -> c.select(box + "/Renamed"));
			assertEquals("NO", e.getStatus());
			assertNull(c.getSelected());
		}
	}

	/** IMAP4rev1 without UTF8=ACCEPT: names travel as modified UTF-7. */
	@Test
	public void testRev1ModifiedUtf7() throws Exception {
		String box = newMailbox("Grüße");
		ImapClientConfig cfg = config().setEnableRev2(false).setEnableUtf8(false);
		List<String> trace = Collections.synchronizedList(new ArrayList<>());
		try (ImapClient c = new ImapClient(cfg)) {
			c.setTrace(trace::add);
			c.connect();
			c.login("tony", "secret");
			assertFalse(c.isRev2());
			assertFalse(c.isUtf8());
			c.create(box);
			assertTrue(c.listAll().stream().anyMatch(m -> m.getName().equals(box)));
			long uid = append(c, box, TestImapServer.SIMPLE);
			SelectedMailbox s = c.select(box);
			assertEquals(1, s.getExists());
			assertEquals(Arrays.asList(uid), s.getUids());
			assertEquals("Test 1", c.fetchSummaries("1:*").get(0).getEnvelope().getSubject());
			List<Long> found = c.search(SearchCriteria.subject("Test"));
			assertEquals(Arrays.asList(uid), found);
		}
		assertTrue(trace.stream().anyMatch(t -> t.contains("Gr&APwA3w-e")), "modified UTF-7 on the wire");
	}

	// ------------------------------------------------------------------ messages

	@Test
	public void testSummariesAndParts() throws Exception {
		String box = newMailbox("Parts");
		try (ImapClient c = login()) {
			c.create(box);
			long u1 = append(c, box, TestImapServer.SIMPLE, "\\Seen");
			long u2 = append(c, box, TestImapServer.MULTIPART);
			long u3 = append(c, box, TestImapServer.UTF8);
			assertTrue(u1 > 0 && u2 > u1 && u3 > u2);

			SelectedMailbox s = c.select(box);
			assertFalse(s.isReadOnly());
			assertEquals(3, s.getExists());
			assertEquals(Arrays.asList(u1, u2, u3), s.getUids());
			assertTrue(s.getPermanentFlags().contains("\\Seen"));

			List<MessageSummary> list = c.fetchSummaries("1:*");
			assertEquals(3, list.size());
			MessageSummary m1 = list.get(0);
			assertEquals(u1, m1.getUid());
			assertTrue(m1.isSeen());
			assertNotNull(m1.getInternalDate());
			assertEquals(bytes(TestImapServer.SIMPLE).length, m1.getSize());
			Envelope e = m1.getEnvelope();
			assertEquals("Test 1", e.getSubject());
			assertEquals("Fred Foo", e.getFrom().get(0).getDisplayName());
			assertEquals("fred@example.com", e.getFrom().get(0).toString().replaceAll(".*<|>", ""));
			assertEquals("<m1@example.com>", e.getMessageId());
			assertNotNull(e.getDate());
			assertFalse(m1.hasAttachments());
			assertEquals("TEXT/PLAIN", m1.getStructure().getMimeType().toUpperCase());
			assertEquals("Hello\r\nWorld\r\n", c.fetchText(u1, m1.getStructure()));

			MessageSummary m2 = list.get(1);
			assertFalse(m2.isSeen());
			assertTrue(m2.hasAttachments());
			BodyPart root = m2.getStructure();
			assertTrue(root.isMultipart());
			BodyPart text = root.findText("PLAIN");
			assertEquals("1", text.getPartNumber());
			assertEquals("Hällo Welt", c.fetchText(u2, text), "the CRLF before the boundary belongs to the boundary");
			BodyPart bin = root.getChildren().get(1);
			assertEquals("2", bin.getPartNumber());
			assertEquals("data.bin", bin.getFilename());
			assertTrue(bin.isAttachment());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			c.fetchPart(u2, bin, out, null);
			assertArrayEquals(new byte[] {0, 1, 2, 3, 4}, out.toByteArray());
			BodyPart inner = root.getChildren().get(2);
			assertTrue(inner.isAttachedMessage());
			assertEquals("inner", inner.getAttachedEnvelope().getSubject());

			Envelope e3 = list.get(2).getEnvelope();
			assertEquals("Grüße", e3.getSubject());
			assertEquals("José", e3.getFrom().get(0).getDisplayName());

			Message whole = c.fetchMessage(u2);
			assertEquals("parts", whole.getSubject());
			Message headers = c.fetchHeaders(u1);
			assertEquals("Test 1", headers.getSubject());
			assertFalse(c.fetchFlags(Long.toString(u2)).get(u2).contains("\\Seen"), "fetches don't set \\Seen");
		}
	}

	@Test
	public void testSearchFlagsCopyMoveDelete() throws Exception {
		String box = newMailbox("Work");
		String archive = newMailbox("Archive");
		try (ImapClient c = login()) {
			c.create(box);
			c.create(archive);
			long u1 = append(c, box, TestImapServer.SIMPLE);
			long u2 = append(c, box, TestImapServer.MULTIPART);
			long u3 = append(c, box, TestImapServer.UTF8);
			c.select(box);

			assertEquals(Arrays.asList(u1, u2, u3), c.search("ALL"));
			assertEquals(Arrays.asList(u3), c.search(SearchCriteria.subject("Grüße")));
			assertEquals(Arrays.asList(u1), c.search(SearchCriteria.and(SearchCriteria.from("fred"),
					SearchCriteria.since(LocalDate.of(2000, 1, 1)))));
			assertEquals(Arrays.asList(u1, u3),
					c.search(SearchCriteria.or(SearchCriteria.subject("Test"), SearchCriteria.subject("Grüße"))));

			c.addFlags(u1 + "," + u3, "\\Flagged", "\\Seen");
			assertEquals(Arrays.asList(u1, u3), c.search(SearchCriteria.flagged()));
			c.removeFlags(Long.toString(u3), "\\Flagged");
			assertEquals(Arrays.asList(u2, u3), c.search("NOT FLAGGED"));
			c.setFlags(Long.toString(u2), "$Work");
			assertEquals(Collections.singleton("$Work"), c.fetchFlags(Long.toString(u2)).get(u2));

			ImapClient.CopyResult cr = c.copy(Long.toString(u1), archive);
			assertEquals(1, cr.uids.size());
			assertTrue(cr.uidValidity > 0);
			ImapClient.CopyResult mr = c.move(u2 + ":" + u3, archive);
			assertEquals(2, mr.uids.size());
			assertEquals(Arrays.asList(u1), c.getSelected().getUids());
			assertEquals(1, c.getSelected().getExists());

			c.deleteMessages(Long.toString(u1));
			assertEquals(0, c.getSelected().getExists());
			assertEquals(3, c.status(archive).getMessages());
			c.unselect();
			assertEquals(ImapClient.State.AUTHENTICATED, c.getState());
		}
	}

	/** EXAMINE is read-only; STORE is refused. */
	@Test
	public void testExamine() throws Exception {
		String box = newMailbox("ReadOnly");
		try (ImapClient c = login()) {
			c.create(box);
			long uid = append(c, box, TestImapServer.SIMPLE);
			SelectedMailbox s = c.examine(box);
			assertTrue(s.isReadOnly());
			assertThrows(ImapException.class, () -> c.addFlags(Long.toString(uid), "\\Flagged"));
			assertThrows(IllegalStateException.class, () -> {
				c.closeMailbox();
				c.fetchSummaries("1:*");
			});
		}
	}

	@Test
	public void testAsyncSubmit() throws Exception {
		try (ImapClient c = login()) {
			CompletableFuture<Integer> f = c.submit(x -> x.listAll().size());
			assertTrue(f.get(30, TimeUnit.SECONDS) > 0);
			CompletableFuture<Object> bad = c.submit(x -> x.select("NoSuchBox" + names.incrementAndGet()));
			Exception e = assertThrows(Exception.class, () -> bad.get(30, TimeUnit.SECONDS));
			assertTrue(e.getCause() instanceof ImapException);
		}
	}

	// ------------------------------------------------------------------ IDLE

	@Test
	public void testIdleEvents() throws Exception {
		String box = newMailbox("Idle");
		BlockingQueue<String> events = new LinkedBlockingQueue<>();
		ImapClientConfig cfg = config();
		try (ImapClient watcher = login(cfg, "tony", "secret"); ImapClient other = login()) {
			watcher.create(box);
			long first = append(watcher, box, TestImapServer.SIMPLE);
			watcher.select(box);
			watcher.addListener(new ImapListener() {
				@Override
				public void exists(String mailbox, long count) {
					events.add("exists " + count);
				}

				@Override
				public void expunged(String mailbox, long msn, long uid) {
					events.add("expunged " + msn + " " + uid);
				}

				@Override
				public void flagsChanged(String mailbox, long msn, long uid, Set<String> flags) {
					events.add("flags " + uid + " " + flags.contains("\\Flagged"));
				}
			});
			watcher.startIdle();
			assertTrue(watcher.isIdling());

			long second = append(other, box, TestImapServer.UTF8);
			assertEquals("exists 2", events.poll(20, TimeUnit.SECONDS));

			other.select(box);
			other.addFlags(Long.toString(first), "\\Flagged");
			assertEquals("flags " + first + " true", events.poll(20, TimeUnit.SECONDS));

			other.deleteMessages(Long.toString(first));
			assertEquals("expunged 1 " + first, events.poll(20, TimeUnit.SECONDS));

			// a command while idling stops IDLE, runs, and IDLE resumes
			assertEquals(Arrays.asList(second), watcher.search("ALL"));
			assertTrue(watcher.isIdling());
			assertEquals(Arrays.asList(second), watcher.getSelected().getUids());
			long third = append(other, box, TestImapServer.SIMPLE);
			awaitEvent(events, "exists 2");
			watcher.noop();
			assertEquals(Arrays.asList(second, third), watcher.getSelected().getUids());

			watcher.stopIdle();
			assertFalse(watcher.isIdling());
			watcher.noop();
		}
	}

	/** Wait for an event, skipping others (a server may repeat EXISTS after EXPUNGE). */
	private static void awaitEvent(BlockingQueue<String> events, String expected) throws InterruptedException {
		List<String> seen = new ArrayList<>();
		long end = System.currentTimeMillis() + 20000;
		while (System.currentTimeMillis() < end) {
			String e = events.poll(500, TimeUnit.MILLISECONDS);
			if (expected.equals(e)) {
				return;
			}
			if (e != null) {
				seen.add(e);
			}
		}
		throw new AssertionError("No event " + expected + "; got " + seen);
	}

	@Test
	public void testDisconnectEvent() throws Exception {
		BlockingQueue<String> events = new LinkedBlockingQueue<>();
		ImapClient c = login();
		c.addListener(new ImapListener() {
			@Override
			public void disconnected(Exception cause) {
				events.add(cause == null ? "logout" : "lost");
			}
		});
		c.logout();
		assertEquals("logout", events.poll(10, TimeUnit.SECONDS));
		assertThrows(IOException.class, () -> c.noop());
		c.close();
	}

	// ------------------------------------------------------------------ large messages

	/** Appends and fetches a message larger than the test heap (64 MB), with progress. */
	@Test
	public void testLargeMessage() throws Exception {
		String box = newMailbox("Big");
		Random random = new Random(7);
		byte[] chunk = new byte[57 * 1024];
		random.nextBytes(chunk);
		StringBuilder lines = new StringBuilder();
		for (int i = 0; i < chunk.length; i += 57) {
			lines.append(Base64.getEncoder().encodeToString(Arrays.copyOfRange(chunk, i, i + 57))).append("\r\n");
		}
		byte[] block = bytes(lines.toString());
		int blocks = 900; // about 70 MB
		byte[] head = bytes("From: big@example.com\r\nSubject: big\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: multipart/mixed; boundary=XX\r\n\r\n--XX\r\nContent-Type: text/plain\r\n\r\nsee attached\r\n"
				+ "--XX\r\nContent-Type: application/octet-stream; name=big.bin\r\n"
				+ "Content-Transfer-Encoding: base64\r\n\r\n");
		byte[] tail = bytes("--XX--\r\n");
		long total = head.length + (long) block.length * blocks + tail.length;
		MessageDigest sent = MessageDigest.getInstance("SHA-256");
		InputStream source = new InputStream() {
			long pos;

			@Override
			public int read() {
				byte[] b = new byte[1];
				return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				if (pos >= total) {
					return -1;
				}
				byte[] src;
				long start;
				long blockEnd = head.length + (long) block.length * blocks;
				if (pos < head.length) {
					src = head;
					start = 0;
				} else if (pos < blockEnd) {
					src = block;
					start = head.length + (pos - head.length) / block.length * block.length;
				} else {
					src = tail;
					start = blockEnd;
				}
				int at = (int) (pos - start);
				int n = Math.min(len, src.length - at);
				System.arraycopy(src, at, b, off, n);
				sent.update(src, at, n);
				pos += n;
				return n;
			}
		};
		AtomicLong lastProgress = new AtomicLong();
		try (ImapClient c = login()) {
			c.create(box);
			long uid = c.append(box, source, total, null, null, (done, all) -> lastProgress.set(done));
			assertEquals(total, lastProgress.get());
			c.select(box);
			assertEquals(total, c.fetchSummaries(Long.toString(uid)).get(0).getSize());

			MessageDigest got = MessageDigest.getInstance("SHA-256");
			lastProgress.set(0);
			long n = c.fetchMessage(uid, digest(got), false, (done, all) -> lastProgress.set(done));
			assertEquals(total, n);
			assertEquals(total, lastProgress.get());
			assertTrue(MessageDigest.isEqual(sent.digest(), got.digest()), "content survives the round trip");

			// the attachment, decoded by the server (BINARY)
			BodyPart part = c.fetchSummaries(Long.toString(uid)).get(0).getStructure().getChildren().get(1);
			assertEquals("big.bin", part.getFilename());
			MessageDigest expected = MessageDigest.getInstance("SHA-256");
			for (int i = 0; i < blocks; i++) {
				expected.update(chunk);
			}
			MessageDigest decoded = MessageDigest.getInstance("SHA-256");
			assertEquals((long) chunk.length * blocks, c.fetchPart(uid, part, digest(decoded), null));
			assertTrue(MessageDigest.isEqual(expected.digest(), decoded.digest()), "decoded attachment");
			c.deleteMessages(Long.toString(uid));
		}
	}

	private static OutputStream digest(MessageDigest md) {
		return new OutputStream() {
			@Override
			public void write(int b) {
				md.update((byte) b);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				md.update(b, off, len);
			}
		};
	}

	// ------------------------------------------------------------------ the command-line program

	@Test
	public void testCliScript() throws Exception {
		String box = newMailbox("Cli");
		File dir = Files.createTempDirectory("imapcli").toFile();
		File eml = new File(dir, "in.eml");
		Files.write(eml.toPath(), bytes(TestImapServer.MULTIPART));
		File saved = new File(dir, "out.eml");
		File part = new File(dir, "part.bin");
		String script = String.join("\n",
				"# a script on standard input",
				"create " + box,
				"put " + box + " \"" + eml.getPath() + "\" \\Flagged",
				"select " + box,
				"ls",
				"parts 1",
				"show 1",
				"get 1 2 \"" + part.getPath() + "\"",
				"save 1 \"" + saved.getPath() + "\"",
				"search FROM \"a@example.com\"",
				"flag 1 $Done",
				"list " + box + "*",
				"status " + box,
				"nosuchcommand",
				"rm 1",
				"close",
				"delete " + box,
				"quit");
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		int code = ImapCli.run(new String[] {"--host", "localhost", "--port", Integer.toString(port), "--plain",
				"--trust-all", "--user", "tony", "--password", "secret"},
				new ByteArrayInputStream(bytes(script)), new PrintStream(outBytes, true, "UTF-8"),
				new PrintStream(errBytes, true, "UTF-8"));
		String out = outBytes.toString("UTF-8");
		assertEquals(1, code, "one command failed\n" + out + errBytes.toString("UTF-8"));
		assertTrue(out.contains("Appended "), out);
		assertTrue(out.contains(box + ": 1 messages"), out);
		assertTrue(out.matches("(?s).*\\n\\s+1 NF  @ .*parts.*"), out);
		assertTrue(out.contains("2  APPLICATION/OCTET-STREAM") || out.contains("2  application/octet-stream"), out);
		assertTrue(out.contains("Hällo Welt"), out);
		assertTrue(out.contains("[part 2] data.bin"), out);
		assertTrue(out.contains("1 found: 1"), out);
		assertTrue(out.contains("Unknown command nosuchcommand"), out);
		assertTrue(out.contains("Deleted " + box), out);
		assertArrayEquals(new byte[] {0, 1, 2, 3, 4}, Files.readAllBytes(part.toPath()));
		assertArrayEquals(bytes(TestImapServer.MULTIPART), Files.readAllBytes(saved.toPath()));
		for (File f : dir.listFiles()) {
			f.delete();
		}
		dir.delete();
	}

	@Test
	public void testCliOneShotAndUsage() throws Exception {
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(outBytes, true, "UTF-8");
		PrintStream err = new PrintStream(errBytes, true, "UTF-8");
		InputStream none = new ByteArrayInputStream(new byte[0]);

		assertEquals(0, ImapCli.run(new String[] {"--host", "localhost", "--port", Integer.toString(port), "--starttls",
				"--trust-all", "--user", "tony", "--password", "secret", "status", "INBOX"}, none, out, err),
				errBytes.toString("UTF-8"));
		assertTrue(outBytes.toString("UTF-8").startsWith("INBOX: "), outBytes.toString("UTF-8"));

		assertEquals(1, ImapCli.run(new String[] {"--host", "localhost", "--port", Integer.toString(port), "--plain",
				"--trust-all", "--user", "tony", "--password", "nope", "list"}, none, out, err));
		assertTrue(errBytes.toString("UTF-8").contains("Error: "), errBytes.toString("UTF-8"));

		assertEquals(2, ImapCli.run(new String[] {"--port", "1"}, none, out, err));
		assertEquals(2, ImapCli.run(new String[] {"--host"}, none, out, err));
	}

	/** Each call made while another runs from a second thread is serialized. */
	@Test
	public void testThreads() throws Exception {
		String box = newMailbox("Threads");
		try (ImapClient c = login()) {
			c.create(box);
			c.select(box);
			List<Thread> threads = new ArrayList<>();
			List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
			for (int t = 0; t < 4; t++) {
				Thread th = new Thread(() -> {
					try {
						for (int i = 0; i < 5; i++) {
							append(c, box, TestImapServer.SIMPLE);
							c.noop();
						}
					} catch (Throwable e) {
						errors.add(e);
					}
				});
				threads.add(th);
				th.start();
			}
			for (Thread th : threads) {
				th.join(60000);
			}
			assertTrue(errors.isEmpty(), errors.toString());
			assertEquals(20, c.search("ALL").size());
			Map<Long, Set<String>> flags = c.fetchFlags("1:*");
			assertEquals(20, flags.size());
		}
	}
}
