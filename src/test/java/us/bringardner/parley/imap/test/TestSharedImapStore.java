package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.mail.store.AnotherServer;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.pop3.test.TestPop3Server;

/** An IMAP server on a store shared with another server ({@code parley.mail.sharedStore=cluster}). */
public class TestSharedImapStore {

	private static String before;
	private static ImapServer server;
	private static FileSource root;
	private static AnotherServer other;

	@BeforeAll
	public static void start() throws Exception {
		String keystore = java.nio.file.Files.createTempDirectory("parley-imap-shared").resolve("keystore.p12").toString();
		TestPop3Server.makeTestKeystore(new File(keystore));
		System.setProperty("ImapServer.KeyStoreName", keystore);
		System.setProperty("ImapServer.KeyStorePassword", TestPop3Server.KEYSTORE_PASSWORD);
		System.setProperty("ImapServer.KeyStoreType", "PKCS12");
		System.setProperty("ImapServer." + FileBasedAcl.PROP_FILE_NAME, "Pop3TestAcl.txt");
		System.setProperty("ImapServer." + IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getName());
		before = System.getProperty(MailLocks.SHARED_STORE_PROPERTY);
		System.setProperty(MailLocks.SHARED_STORE_PROPERTY, "cluster");
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("imapshared");
		server = new ImapServer(0, ImapServer.IMAP_NAME, false);
		server.setMaildropRoot(root);
		server.setLoginFailureDelay(0);
		server.getLogger().setLevel(Level.ERROR);
		server.startAndWait(10000);
		other = new AnotherServer(root, "other");
	}

	@AfterAll
	public static void stop() throws Exception {
		if (server != null) {
			server.stop();
		}
		if (other != null) {
			other.close();
		}
		if (before == null) {
			System.clearProperty(MailLocks.SHARED_STORE_PROPERTY);
		} else {
			System.setProperty(MailLocks.SHARED_STORE_PROPERTY, before);
		}
	}

	private static String find(List<String> lines, String start) {
		for (String l : lines) {
			if (l.startsWith(start)) {
				return l;
			}
		}
		return null;
	}

	@Test
	public void anotherServersMessageReachesTheSession() throws Exception {
		FileSource dir = server.getInboxDirectory("tony").getChild(us.bringardner.parley.mail.store.MailStore.MAILBOXES).getChild("shared1");
		Mailbox theirs = other.open(dir);
		other.append(theirs, "first");
		try (ImapClient c = new ImapClient(server.getLocalPort())) {
			c.login("tony", "secret");
			List<String> r = c.cmd("SELECT shared1");
			assertTrue(r.contains("* 1 EXISTS"), r.toString());
			other.append(theirs, "second");
			r = c.cmd("NOOP");
			assertTrue(r.contains("* 2 EXISTS"), "seen at the next NOOP: " + r);
			c.cmd("LOGOUT");
		}
	}

	@Test
	public void aMailboxRecreatedByAnotherServerIsClosed() throws Exception {
		FileSource dir = server.getInboxDirectory("tony").getChild(us.bringardner.parley.mail.store.MailStore.MAILBOXES).getChild("shared2");
		other.append(other.open(dir), "old");
		try (ImapClient c = new ImapClient(server.getLocalPort())) {
			c.login("tony", "secret");
			List<String> r = c.cmd("SELECT shared2");
			String validity = find(r, "* OK [UIDVALIDITY ");
			assertTrue(validity != null, r.toString());
			// the other server deletes it and makes it again: a new UIDVALIDITY (in seconds)
			other.deleteMailbox(dir);
			Thread.sleep(1100);
			other.append(other.open(dir), "new");
			r = c.cmd("NOOP");
			assertTrue(find(r, "* OK [CLOSED]") != null, "the session must select it again: " + r);
			assertTrue(r.get(r.size() - 1).contains(" OK "), r.toString());
			r = c.cmd("FETCH 1 FLAGS");
			assertTrue(r.get(r.size() - 1).contains(" BAD ") || r.get(r.size() - 1).contains(" NO "), "nothing selected now: " + r);
			r = c.cmd("SELECT shared2");
			String again = find(r, "* OK [UIDVALIDITY ");
			assertNotEquals(validity, again, "the new UIDVALIDITY");
			assertTrue(r.contains("* 1 EXISTS"), r.toString());
			c.cmd("LOGOUT");
		}
	}

	@Test
	public void anExpungeAfterTheMailboxWasRecreatedRemovesNothing() throws Exception {
		FileSource dir = server.getInboxDirectory("tony").getChild(us.bringardner.parley.mail.store.MailStore.MAILBOXES).getChild("shared4");
		other.append(other.open(dir), "old");
		try (ImapClient c = new ImapClient(server.getLocalPort())) {
			c.login("tony", "secret");
			c.cmd("SELECT shared4");
			List<String> r = c.cmd("STORE 1 +FLAGS (\\Deleted)");
			assertTrue(r.get(r.size() - 1).contains(" OK "), r.toString());
			// the other server deletes it and makes it again, with a message of the same UID
			other.deleteMailbox(dir);
			Mailbox theirs = other.open(dir);
			other.append(theirs, "new");
			r = c.cmd("EXPUNGE");
			assertTrue(find(r, "* OK [CLOSED]") != null, "closed: " + r);
			assertTrue(find(r, "* 1 EXPUNGE") == null, "and nothing expunged: " + r);
			c.cmd("LOGOUT");
			theirs.refresh(true);
			assertEquals(1, theirs.count(), "the other server's new message is still there");
		}
	}

	@Test
	public void flagsSetHereReachTheOtherServer() throws Exception {
		FileSource dir = server.getInboxDirectory("tony").getChild(us.bringardner.parley.mail.store.MailStore.MAILBOXES).getChild("shared3");
		Mailbox theirs = other.open(dir);
		other.append(theirs, "flag me");
		try (ImapClient c = new ImapClient(server.getLocalPort())) {
			c.login("tony", "secret");
			c.cmd("SELECT shared3");
			List<String> r = c.cmd("STORE 1 +FLAGS (\\Flagged)");
			assertTrue(r.get(r.size() - 1).contains(" OK "), r.toString());
			c.cmd("LOGOUT");
		}
		theirs.refresh(true);
		assertEquals(1, theirs.count());
		assertTrue(Mailbox.hasFlag(theirs.getMessages().get(0).getFlags(theirs), "\\Flagged"), "saved for the other server");
	}
}
