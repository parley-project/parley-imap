package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.mail.test.MailTestSupport.deleteAll;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.mail.store.MailboxAcl;
import us.bringardner.parley.mail.store.ShareIndex;
import us.bringardner.parley.net.ServerTestSupport;

/**
 * RFC 4314 ACLs over IMAP: SETACL, DELETEACL, GETACL, LISTRIGHTS and MYRIGHTS, the rights each
 * command checks, the Other Users namespace, LIST filtered by l, groups, anyone, negative
 * rights and the server's permissions as a ceiling.
 */
public class TestImapAcl {

	private static final String MSG = "From: a@example.com\r\nSubject: hi\r\n\r\nHello\r\n";

	private static ImapServer server;
	private static FileSource root;
	private static int port;

	@BeforeAll
	public static void startServer() throws Exception {
		root = FileSourceFactory.getDefaultFactory().createTempDirectory("imapacl");
		us.bringardner.parley.mail.store.MailLocks.writeStoreSettings(root, us.bringardner.parley.net.lock.Sharing.OFF);
		server = new ImapServer(0, ImapServer.IMAP_NAME, false);
		server.setMaildropRoot(root);
		server.setLoginFailureDelay(0);
		server.getLogger().setLevel(Level.ERROR);
		server.setUserRegistry(ServerTestSupport.registry(
				"alice@x.us, pw, READ|WRITE",
				"s1@x.us, pw, READ|WRITE, maildrop=shared/box",
				"olga@x.us, pw, READ|WRITE",
				"erin@x.us, pw, READ|WRITE",
				"s2@x.us, pw, READ|WRITE, maildrop=shared/box",
				"bob@x.us, pw, READ|WRITE",
				"carol@x.us, pw, READ|WRITE, groups=sales@x.us",
				"dave@other.com, pw, READ|WRITE",
				"reader@x.us, pw, READ",
				"admin@x.us, pw, READ|WRITE|ADMIN"));
		server.startAndWait(10000);
		port = server.getLocalPort();

		try (ImapClient a = login("alice@x.us")) {
			a.ok("CREATE Projects");
			assertTrue(ImapClient.last(a.append("Projects", null, bytes(MSG))).contains("OK"));
		}
	}

	@AfterAll
	public static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
		if (root != null) {
			deleteAll(root);
		}
	}

	private static ImapClient login(String user) throws IOException {
		ImapClient c = new ImapClient(port);
		c.login(user, "pw");
		return c;
	}

	static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static String tagged(List<String> r) {
		return ImapClient.last(r);
	}

	private static boolean has(List<String> r, String line) {
		return r.contains(line);
	}

	private static boolean any(List<String> r, String part) {
		for (String s : r) {
			if (s.contains(part)) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void ownersHaveEverythingAndAdministerTheirMailboxes() throws Exception {
		try (ImapClient a = login("alice@x.us")) {
			String caps = a.ok("CAPABILITY").get(0);
			assertTrue(caps.contains(" ACL") && caps.contains(" RIGHTS=texk"), caps);
			assertEquals("* MYRIGHTS INBOX lrswipkxteacd", a.ok("MYRIGHTS INBOX").get(0));
			assertEquals("* ACL INBOX alice@x.us lrswipkxteacd", a.ok("GETACL INBOX").get(0), "no file: the owner's default");
			assertEquals("* LISTRIGHTS INBOX alice@x.us la r s w i p k x t e", a.ok("LISTRIGHTS INBOX alice@x.us").get(0).replace(" c d", ""));
			assertTrue(a.ok("LISTRIGHTS INBOX alice@x.us").get(0).endsWith(" e c d"), "the obsolete c and d can be granted");
			assertEquals("* LISTRIGHTS INBOX bob@x.us \"\" l r s w i p k x t e a c d", a.ok("LISTRIGHTS INBOX bob@x.us").get(0));

			assertTrue(tagged(a.cmd("SETACL INBOX bob@x.us lZ")).contains("BAD"), "not a right");
			assertTrue(tagged(a.cmd("SETACL INBOX bob@x.us R")).contains("BAD"), "upper case");
			assertTrue(tagged(a.cmd("SETACL INBOX nobody@x.us lr")).contains("NO"), "no such user");
			assertTrue(tagged(a.cmd("SETACL INBOX $sales lr")).contains("NO"), "a group needs its domain");
			assertTrue(tagged(a.cmd("SETACL Nope bob@x.us lr")).contains("NO [NONEXISTENT]"));

			// can't lock herself out
			a.ok("SETACL INBOX alice@x.us \"\"");
			assertEquals("* MYRIGHTS INBOX la", a.ok("MYRIGHTS INBOX").get(0));
			a.ok("SETACL INBOX alice@x.us lrswipkxtea");
			assertEquals("* MYRIGHTS INBOX lrswipkxteacd", a.ok("MYRIGHTS INBOX").get(0));
			a.ok("SETACL INBOX alice@x.us \"\"");
			a.ok("DELETEACL INBOX alice@x.us");
			assertEquals("* MYRIGHTS INBOX lrswipkxteacd", a.ok("MYRIGHTS INBOX").get(0), "no entry of her own: every right again");
		}
	}

	@Test
	public void sharedMailboxesAreInOtherUsers() throws Exception {
		try (ImapClient a = login("alice@x.us")) {
			a.ok("SETACL Projects bob@x.us lr");
			assertEquals("* ACL Projects alice@x.us lrswipkxteacd bob@x.us lr", a.ok("GETACL Projects").get(0));
		}
		FileSource file = server.getInboxDirectory("alice@x.us").getChild(".mailboxes").getChild("_projects").getChild(MailboxAcl.FILE);
		assertTrue(file.exists(), "in the mailbox's folder");
		assertTrue(root.getChild(ShareIndex.FILE).exists(), "the share index");

		try (ImapClient b = login("bob@x.us")) {
			assertEquals("* NAMESPACE ((\"\" \"/\")) ((\"Other Users/\" \"/\")) NIL", b.ok("NAMESPACE").get(0));
			List<String> list = b.ok("LIST \"\" \"*\"");
			assertTrue(has(list, "* LIST (\\Noselect \\HasChildren) \"/\" \"Other Users\""), list.toString());
			assertTrue(has(list, "* LIST (\\HasNoChildren) \"/\" \"Other Users/alice@x.us/Projects\""), list.toString());
			assertTrue(has(list, "* LIST (\\Noselect \\HasChildren) \"/\" \"Other Users/alice@x.us\""),
					"no rights on alice's INBOX: only her level, \\Noselect: " + list);
			assertTrue(any(b.ok("LIST \"Other Users/\" \"%/%\""), "Other Users/alice@x.us/Projects"));

			List<String> sel = b.ok("SELECT \"Other Users/alice@x.us/Projects\"");
			assertTrue(tagged(sel).contains("[READ-ONLY]"), sel.toString());
			assertTrue(has(sel, "* OK [PERMANENTFLAGS ()] No permanent flags permitted"), sel.toString());
			b.ok("FETCH 1 BODY[]");
			assertFalse(any(b.ok("FETCH 1 FLAGS"), "\\Seen"), "no s: \\Seen isn't set");
			assertTrue(tagged(b.cmd("STORE 1 +FLAGS (\\Seen)")).contains("NO"), "read only");
			assertEquals("* MYRIGHTS \"Other Users/alice@x.us/Projects\" lr", b.ok("MYRIGHTS \"Other Users/alice@x.us/Projects\"").get(0));
			assertTrue(tagged(b.cmd("GETACL \"Other Users/alice@x.us/Projects\"")).contains("NO [NOPERM]"), "no a");
			assertTrue(tagged(b.cmd("SELECT \"Other Users/alice@x.us\"")).contains("NO [NONEXISTENT]"), "can't see it: as if it weren't there");
			assertTrue(tagged(b.cmd("SELECT \"Other Users/nobody@x.us\"")).contains("NO [NONEXISTENT]"));
			assertTrue(tagged(b.append("\"Other Users/alice@x.us/Projects\"", null, bytes(MSG))).contains("NO [NOPERM]"), "no i");
			assertTrue(tagged(b.cmd("CREATE \"Other Users/alice@x.us/Projects/Sub\"")).contains("NO [NOPERM]"), "no k");
			assertTrue(tagged(b.cmd("CREATE \"Other Users/alice@x.us/Top\"")).contains("NO"), "not in another user's top level");
			assertTrue(tagged(b.cmd("DELETE \"Other Users/alice@x.us/Projects\"")).contains("NO [NOPERM]"), "no x");
			assertTrue(tagged(b.cmd("RENAME \"Other Users/alice@x.us/Projects\" Mine")).contains("NO"));
			assertTrue(tagged(b.cmd("STATUS \"Other Users/alice@x.us/Projects\" (MESSAGES)")).contains("OK"));

			b.ok("SUBSCRIBE \"Other Users/alice@x.us/Projects\"");
			assertTrue(has(b.ok("LSUB \"\" \"*\""), "* LSUB () \"/\" \"Other Users/alice@x.us/Projects\""));
		}
	}

	@Test
	public void groupsAnyoneAndNegativeRights() throws Exception {
		try (ImapClient a = login("alice@x.us")) {
			a.ok("CREATE Sales");
			assertTrue(tagged(a.append("Sales", null, bytes(MSG))).contains("OK"));
			a.ok("SETACL Sales $sales@x.us lrs");
		}
		try (ImapClient c = login("carol@x.us")) {
			List<String> sel = c.ok("SELECT \"Other Users/alice@x.us/Sales\"");
			assertTrue(tagged(sel).contains("[READ-WRITE]"), "s: a flag carol may change");
			assertTrue(has(sel, "* OK [PERMANENTFLAGS (\\Seen)] Flags permitted"), sel.toString());
			assertTrue(tagged(c.cmd("STORE 1 +FLAGS (\\Flagged)")).contains("NO [NOPERM]"), "no w");
			List<String> st = c.ok("STORE 1 +FLAGS (\\Seen \\Flagged)");
			assertTrue(any(st, "\\Seen") && !any(st, "\\Flagged"), "only what the rights allow: " + st);
			assertTrue(tagged(c.cmd("EXPUNGE")).contains("NO [NOPERM]"), "no e");
			assertTrue(tagged(c.cmd("MOVE 1 INBOX")).contains("NO [NOPERM]"), "MOVE needs t and e");
			c.ok("COPY 1 INBOX");
			c.ok("CLOSE");
		}
		try (ImapClient d = login("dave@other.com")) {
			assertFalse(any(d.ok("LIST \"\" \"*\""), "Other Users"), "nothing shared with dave");
		}
		try (ImapClient a = login("alice@x.us")) {
			a.ok("SETACL Sales anyone l");
			a.ok("SETACL Sales -carol@x.us s");
		}
		try (ImapClient d = login("dave@other.com")) {
			assertTrue(any(d.ok("LIST \"\" \"*\""), "\"Other Users/alice@x.us/Sales\""), "anyone is every user");
			assertTrue(tagged(d.cmd("SELECT \"Other Users/alice@x.us/Sales\"")).contains("NO [NOPERM]"), "l without r");
			assertEquals("* MYRIGHTS \"Other Users/alice@x.us/Sales\" l", d.ok("MYRIGHTS \"Other Users/alice@x.us/Sales\"").get(0));
		}
		try (ImapClient c = login("carol@x.us")) {
			assertEquals("* MYRIGHTS \"Other Users/alice@x.us/Sales\" lr", c.ok("MYRIGHTS \"Other Users/alice@x.us/Sales\"").get(0),
					"-carol takes s away whatever gives it");
		}
		try (ImapClient a = login("alice@x.us")) {
			a.ok("DELETEACL Sales anyone");
			a.ok("RENAME Sales Sales2");
			assertTrue(a.ok("GETACL Sales2").get(0).contains("$sales@x.us lrs"), "RENAME keeps the ACL");
		}
		try (ImapClient d = login("dave@other.com")) {
			assertFalse(any(d.ok("LIST \"\" \"*\""), "Other Users"));
		}
	}

	/** To-Do 3.6: COPY and MOVE make the copies with only the flags the target's rights allow */
	@Test
	public void copiesKeepOnlyTheFlagsTheTargetAllows() throws Exception {
		try (ImapClient a = login("alice@x.us")) {
			a.ok("CREATE Drop");
			a.ok("SETACL Drop olga@x.us lri");
			a.ok("CREATE DropSeen");
			a.ok("SETACL DropSeen olga@x.us lris");
			a.ok("CREATE DropAll");
			a.ok("SETACL DropAll olga@x.us lrswite");
		}
		try (ImapClient o = login("olga@x.us")) {
			for (int i = 0; i < 4; i++) {
				assertTrue(tagged(o.append("INBOX", "(\\Seen \\Flagged $Work)", bytes(MSG))).contains("OK"));
			}
			o.ok("SELECT INBOX");
			o.ok("COPY 1 \"Other Users/alice@x.us/Drop\"");
			o.ok("COPY 2 \"Other Users/alice@x.us/DropSeen\"");
			o.ok("COPY 3 \"Other Users/alice@x.us/DropAll\"");
			o.ok("MOVE 4 \"Other Users/alice@x.us/DropSeen\"");
		}
		try (ImapClient a = login("alice@x.us")) {
			a.ok("SELECT Drop");
			List<String> f = a.ok("FETCH 1 FLAGS");
			assertFalse(any(f, "\\Seen") || any(f, "\\Flagged") || any(f, "$Work"), "i only: no flag: " + f);
			a.ok("SELECT DropSeen");
			f = a.ok("FETCH 1:2 FLAGS");
			assertTrue(any(f, "1 FETCH (FLAGS (\\Seen))") && any(f, "2 FETCH (FLAGS (\\Seen))"), "s: \\Seen only, COPY and MOVE: " + f);
			a.ok("SELECT DropAll");
			f = a.ok("FETCH 1 FLAGS");
			assertTrue(any(f, "\\Seen") && any(f, "\\Flagged") && any(f, "$Work"), "s, w: every flag: " + f);
		}
	}

	@Test
	public void theServersPermissionsAreACeilingAndAdminIsNoOneSpecial() throws Exception {
		try (ImapClient r = login("reader@x.us")) {
			assertEquals("* MYRIGHTS INBOX lr", r.ok("MYRIGHTS INBOX").get(0), "no WRITE: read only everywhere");
			assertTrue(tagged(r.cmd("CREATE Mine")).contains("NO [NOPERM]"));
			assertTrue(tagged(r.cmd("SETACL INBOX bob@x.us l")).contains("NO [NOPERM]"), "no a without WRITE");
			assertTrue(tagged(r.ok("SELECT INBOX")).contains("[READ-ONLY]"));
		}
		try (ImapClient a = login("alice@x.us")) {
			a.ok("CREATE ForReader");
			a.ok("SETACL ForReader reader@x.us lrswi");
		}
		try (ImapClient r = login("reader@x.us")) {
			assertEquals("* MYRIGHTS \"Other Users/alice@x.us/ForReader\" lr", r.ok("MYRIGHTS \"Other Users/alice@x.us/ForReader\"").get(0));
		}
		try (ImapClient ad = login("admin@x.us")) {
			assertTrue(tagged(ad.cmd("SELECT \"Other Users/alice@x.us\"")).contains("NO [NONEXISTENT]"), "ADMIN has no special rights");
			assertTrue(tagged(ad.cmd("SETACL \"Other Users/alice@x.us\" admin@x.us lr")).contains("NO [NONEXISTENT]"));
		}
	}

	@Test
	public void createGivesTheCreatorEverythingAndCopiesTheParent() throws Exception {
		try (ImapClient a = login("alice@x.us")) {
			a.ok("CREATE Team");
			a.ok("SETACL Team bob@x.us lrk");
		}
		try (ImapClient b = login("bob@x.us")) {
			b.ok("CREATE \"Other Users/alice@x.us/Team/Bob's\"");
			assertEquals("* MYRIGHTS \"Other Users/alice@x.us/Team/Bob's\" lrswipkxteacd",
					b.ok("MYRIGHTS \"Other Users/alice@x.us/Team/Bob's\"").get(0));
			assertTrue(tagged(b.append("\"Other Users/alice@x.us/Team/Bob's\"", "(\\Seen)", bytes(MSG))).contains("OK"));
		}
		try (ImapClient a = login("alice@x.us")) {
			String acl = a.ok("GETACL \"Team/Bob's\"").get(0);
			assertTrue(acl.contains("alice@x.us lrswipkxteacd") && acl.contains("bob@x.us lrswipkxteacd"), acl);
		}
	}

	@Test
	public void browsingLevelByLevelShowsOnlyWhatMayBeSeen() throws Exception {
		try (ImapClient a = login("olga@x.us")) {
			a.ok("CREATE Work");
			a.ok("CREATE Work/Secret");
			a.ok("SETACL Work erin@x.us lr");
		}
		try (ImapClient d = login("erin@x.us")) {
			List<String> top = d.ok("LIST \"\" \"%\"");
			assertTrue(has(top, "* LIST (\\Noselect \\HasChildren) \"/\" \"Other Users\""), top.toString());
			List<String> users = d.ok("LIST \"Other Users/\" \"%\"");
			assertTrue(has(users, "* LIST (\\Noselect \\HasChildren) \"/\" \"Other Users/olga@x.us\""), "the owner's level: " + users);
			List<String> work = d.ok("LIST \"Other Users/olga@x.us/\" \"%\"");
			assertTrue(has(work, "* LIST (\\HasNoChildren) \"/\" \"Other Users/olga@x.us/Work\""), "Secret isn't told: " + work);
			assertFalse(any(d.ok("LIST \"\" \"*\""), "Secret"));
		}
		try (ImapClient a = login("olga@x.us")) {
			a.ok("SETACL INBOX erin@x.us l");
			a.ok("SETACL Sent erin@x.us l");
		}
		try (ImapClient d = login("erin@x.us")) {
			List<String> users = d.ok("LIST \"Other Users/\" \"%\"");
			assertTrue(has(users, "* LIST (\\HasChildren) \"/\" \"Other Users/olga@x.us\""), "her INBOX, with its visible children: " + users);
			assertTrue(any(d.ok("LIST \"Other Users/\" \"*\""), "\"Other Users/olga@x.us/Sent\""));
			for (String line : d.ok("LIST \"Other Users/\" \"*\"")) {
				assertFalse(line.contains("\\Sent"), "no special use outside the Personal namespace: " + line);
			}
		}
		try (ImapClient a = login("olga@x.us")) {
			a.ok("DELETEACL INBOX erin@x.us");
		}
	}

	@Test
	public void usersWhoShareAMaildropKeepTheirRights() throws Exception {
		try (ImapClient s1 = login("s1@x.us")) {
			s1.ok("SETACL INBOX bob@x.us lr");
		}
		try (ImapClient s2 = login("s2@x.us")) {
			assertEquals("* MYRIGHTS INBOX lrswipkxteacd", s2.ok("MYRIGHTS INBOX").get(0));
			assertTrue(tagged(s2.ok("SELECT INBOX")).contains("[READ-WRITE]"));
		}
	}
}
