package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.mail.test.MailTestSupport.ACL_FILE;

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
import us.bringardner.parley.mail.store.MessageInfo;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.net.ServerTestSupport;

/** An IMAP server on a store shared with another server ({@code parley.mail.sharedStore=cluster}). */
public class TestSharedImapStore {

	private static ImapServer server;
	private static FileSource root;
	private static AnotherServer other;

	@BeforeAll
	public static void start() throws Exception {
		String keystore = java.nio.file.Files.createTempDirectory("parley-imap-shared").resolve("keystore.p12").toString();
		ServerTestSupport.writeKeystore(new File(keystore));
		System.setProperty("parley.mail.imap.keyStoreName", keystore);
		System.setProperty("parley.mail.imap.keyStorePassword", ServerTestSupport.KEYSTORE_PASSWORD);
		System.setProperty("parley.mail.imap.keyStoreType", "PKCS12");
		System.setProperty("parley.mail.imap." + FileBasedAcl.PROP_FILE_NAME, ACL_FILE);
		System.setProperty("parley.mail.imap." + IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getName());
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("imapshared");
		MailLocks.writeStoreSettings(root, us.bringardner.parley.net.lock.Sharing.CLUSTER); // the store's own settings
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

	/**
	 * Another server expunges a message the session still sees (B1 on mx1: Apple Mail syncing
	 * with UID FETCH 1:* failed as a whole). The session's own copy of the mailbox may still list
	 * it (it looks at most once a second) or may have forgotten it; either way the others are
	 * fetched, UID FETCH answers OK, FETCH by number NO [EXPUNGEISSUED], and the EXPUNGE comes
	 * at the next NOOP.
	 */
	@Test
	public void aFetchAfterAnotherServersExpungeAnswersForTheOthers() throws Exception {
		for (boolean waited : new boolean[] {false, true}) {
			FileSource dir = server.getInboxDirectory("tony").getChild(us.bringardner.parley.mail.store.MailStore.MAILBOXES)
					.getChild(waited ? "shared6" : "shared5");
			Mailbox theirs = other.open(dir);
			other.append(theirs, "one");
			MessageInfo two = other.append(theirs, "two");
			other.append(theirs, "three");
			String name = dir.getName();
			try (ImapClient c = new ImapClient(server.getLocalPort())) {
				c.login("tony", "secret");
				List<String> r = c.cmd("SELECT " + name);
				assertTrue(r.contains("* 3 EXISTS"), r.toString());
				assertEquals(List.of(two.getUid()), theirs.expunge(List.of(two.getUid()), null));
				if (waited) {
					Thread.sleep(1100); // the session's copy looks again and forgets it
				}

				r = c.cmd("UID FETCH 1:* (UID BODY.PEEK[])");
				String tag = r.get(r.size() - 1);
				assertTrue(tag.contains(" OK "), "UID FETCH ignores the expunged UID (waited=" + waited + "): " + r);
				assertTrue(r.get(0).startsWith("* 1 FETCH (UID 1 BODY[] {"), r.toString());
				assertTrue(r.get(0).contains("one"), r.toString());
				assertTrue(r.get(1).startsWith("* 3 FETCH (UID 3 BODY[] {"), "message 3 keeps its number: " + r);
				assertTrue(r.get(1).contains("three"), r.toString());
				assertEquals(3, r.size(), r.toString());

				r = c.cmd("FETCH 1:3 (UID RFC822.SIZE BODY.PEEK[HEADER])");
				assertTrue(r.get(0).startsWith("* 1 FETCH (UID 1 "), r.toString());
				assertTrue(r.get(1).startsWith("* 3 FETCH (UID 3 "), r.toString());
				assertTrue(r.get(r.size() - 1).contains(" NO [EXPUNGEISSUED]"), "by number: " + r);
				assertEquals(3, r.size(), r.toString());

				r = c.cmd("UID FETCH 2 (UID BODY.PEEK[TEXT])");
				assertTrue(r.get(r.size() - 1).contains(" OK "), r.toString());
				assertNull(find(r, "* 2 FETCH"), "nothing for the expunged UID alone: " + r);

				Thread.sleep(1100);
				r = c.cmd("NOOP");
				assertTrue(r.contains("* 2 EXPUNGE"), "the EXPUNGE comes now: " + r);
				r = c.cmd("UID FETCH 1:* (UID)");
				assertEquals("* 1 FETCH (UID 1)", r.get(0));
				assertEquals("* 2 FETCH (UID 3)", r.get(1));
				c.cmd("LOGOUT");
			}
		}
	}
}
