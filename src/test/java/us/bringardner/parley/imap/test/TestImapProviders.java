package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.net.server.LaunchSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;

/**
 * The IMAP providers: found by the launcher, settings from parley-mail.properties then
 * parley-imap.properties, the suite's user file (parley.mail.users), imaps off unless its port is set.
 */
public class TestImapProviders {

	/** Settings other tests in this JVM set that would change what these see */
	private static final String[] CLEARED = { "parley.mail.imap.authenticationProvider", "parley.mail.imap.userFile",
			"parley.mail.imap.port", "parley.mail.imaps.port", "parley.mail.imap.secure", "parley.mail.users", "parley.mail.root",
			"parley.mail.imap.keyStoreName" };

	@TempDir
	File dir;

	private final Map<String, String> saved = new HashMap<>();
	private ServerLauncher launcher;

	@BeforeEach
	public void clear() {
		for (String n : CLEARED) {
			saved.put(n, System.getProperty(n));
			System.clearProperty(n);
		}
	}

	@AfterEach
	public void restore() {
		if (launcher != null) {
			launcher.close();
		}
		for (String n : CLEARED) {
			System.clearProperty(n);
			if (saved.get(n) != null) {
				System.setProperty(n, saved.get(n));
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void write(String name, String... lines) throws Exception {
		// (a .properties file reads \ as an escape: the paths in it have / on Windows)
		Files.write(new File(dir, name).toPath(), (String.join("\n", lines).replace('\\', '/') + "\n").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void providersFound() {
		assertTrue(ServerLauncher.providers().keySet().containsAll(Arrays.asList("imap", "imaps")), ServerLauncher.providers().keySet().toString());
	}

	@Test
	public void runsWithTheSuiteSettings() throws Exception {
		int port = freePort();
		File mail = new File(dir, "mail");
		us.bringardner.parley.mail.store.MailLocks.writeStoreSettings(us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()),
				us.bringardner.parley.net.lock.Sharing.OFF); // made by an administrator
		write("users.txt", "tony , secret , READ|WRITE");
		write("parley-mail.properties", "parley.mail.root=" + mail.getPath(), "parley.mail.users=" + new File(dir, "users.txt").getPath(),
				"parley.mail.imap.port=1");
		write("parley-imap.properties", "parley.mail.imap.port=" + port); // wins over the shared file
		launcher = new ServerLauncher("ImapTest", new LaunchSettings(new Properties()), Arrays.asList("imap", "imaps"), dir, new ServerContext());
		launcher.init();
		assertEquals(ServerLauncher.State.OFF, launcher.getState("imaps"), "no imaps port: off");
		launcher.startAll();
		assertEquals(ServerLauncher.State.RUNNING, launcher.getState("imap"));
		assertEquals(port, launcher.getServer("imap").getPort());

		try (Socket s = new Socket("127.0.0.1", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			assertTrue(in.readLine().startsWith("* OK"));
			out.write("a1 LOGIN tony secret\r\n".getBytes(StandardCharsets.US_ASCII));
			String reply = in.readLine();
			while (reply.startsWith("*")) {
				reply = in.readLine();
			}
			assertTrue(reply.startsWith("a1 OK"), "logged in with parley.mail.users: " + reply);
			out.write("a2 LOGOUT\r\n".getBytes(StandardCharsets.US_ASCII));
			in.readLine();
		}
	}

	@Test
	public void legacySecureTurnsOnImapsOnly() throws Exception {
		java.util.Properties c = new java.util.Properties();
		c.setProperty("parley.mail.imap.secure", "true");
		us.bringardner.parley.net.server.ServerProvider imap = ServerLauncher.providers().get("imap");
		us.bringardner.parley.net.server.ServerProvider imaps = ServerLauncher.providers().get("imaps");
		assertTrue(!imap.enabled(c));
		assertTrue(imaps.enabled(c));
		assertTrue(imap.enabled(new java.util.Properties()));
		assertTrue(!imaps.enabled(new java.util.Properties()));
	}
}
