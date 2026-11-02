package us.bringardner.parley.imap.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * ImapCli's --password names where the password is kept (a credential reference), never the
 * password, and there is no $IMAP_PASSWORD (To-Do 2.19).
 */
public class TestImapCliPassword {

	@Test
	public void aPasswordOnTheCommandLineIsRefused() {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int rc = ImapCli.run(new String[] { "--host", "localhost", "--user", "me", "--password", "hunter2" },
				new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true));
		assertEquals(2, rc);
		String text = new String(err.toByteArray(), StandardCharsets.UTF_8);
		assertTrue(text.contains("@env:NAME"), text);
		assertTrue(!text.contains("hunter2"), "the password isn't echoed");
	}

	@Test
	public void aReferenceIsResolved() throws Exception {
		String path = System.getenv("PATH");
		assertEquals(path, ImapCli.resolvePassword("@env:PATH", "localhost", "me"));
	}
}
