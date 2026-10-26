package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.mail.server.AbstractMailServer;

/**
 * IMAP servers in one JVM, each made from its own configuration: the same name gets each server's
 * own value, an IMAPS server reads its own prefix, and the system properties aren't read.
 */
public class TestServerConfiguration {

	@TempDir
	File dir;

	private static Properties props(String... kv) {
		Properties p = new Properties();
		for (int i = 0; i < kv.length; i += 2) {
			p.setProperty(kv[i], kv[i + 1]);
		}
		return p;
	}

	private static ImapServer make(Properties c, boolean secure) throws Exception {
		return AbstractMailServer.makeIn(null, c, () -> new ImapServer(0, ImapServer.IMAP_NAME, secure));
	}

	@Test
	public void oneNameTwoValues() throws Exception {
		ImapServer a = make(props("parley.mail.root", new File(dir, "a").getPath(), "parley.mail.imap.appendLimit", "1000"), false);
		ImapServer b = make(props("parley.mail.root", new File(dir, "b").getPath(), "parley.mail.imap.appendLimit", "2000"), false);
		assertTrue(a.getMaildropRoot().getAbsolutePath().endsWith("a"));
		assertTrue(b.getMaildropRoot().getAbsolutePath().endsWith("b"));
		assertEquals(1000, a.getAppendLimit());
		assertEquals(2000, b.getAppendLimit());
	}

	@Test
	public void imapsReadsItsOwnPrefix() throws Exception {
		Properties c = props("parley.mail.root", dir.getPath(), "parley.mail.imap.requireTls", "true", "parley.mail.imap.autologout", "1900000",
				"parley.mail.imaps.autologout", "2000000", "parley.mail.imap.defaultMailboxes", "false");
		ImapServer imap = make(c, false);
		ImapServer imaps = make(c, true);
		assertTrue(imap.isRequireTls());
		assertFalse(imaps.isRequireTls(), "imap's setting isn't imaps's");
		assertEquals(1900000, imap.getAutologout());
		assertEquals(2000000, imaps.getAutologout());
		assertFalse(imap.isCreateDefaultMailboxes());
		assertTrue(imaps.isCreateDefaultMailboxes());
	}

	@Test
	public void theSystemPropertiesArentRead() throws Exception {
		System.setProperty("parley.mail.imap.appendLimit", "77");
		try {
			ImapServer s = make(props("parley.mail.root", dir.getPath()), false);
			assertEquals(ImapServer.DEFAULT_APPEND_LIMIT, s.getAppendLimit());
		} finally {
			System.clearProperty("parley.mail.imap.appendLimit");
		}
	}
}
