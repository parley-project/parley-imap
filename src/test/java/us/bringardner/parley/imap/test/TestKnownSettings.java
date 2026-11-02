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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.imap.server.ImapServer;
import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;
import us.bringardner.parley.net.server.ServerMain;

/**
 * To-Do 2.16: parley-imap's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out, and every name its servers read while they start and serve a session, so a server's
 * start-up check never warns about a real setting.
 */
public class TestKnownSettings {

	@TempDir
	File dir;

	@Test
	public void everyConstantIsListed() throws Exception {
		assertEquals(List.of(), KnownSettings.load().undeclared(KnownSettings.settingConstants(ImapServer.class)));
	}

	@Test
	public void everyNameTheServersReadIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		assertTrue(k.prefixes().containsAll(List.of(ImapServer.IMAP_PREFIX, ImapServer.IMAPS_PREFIX)), k.prefixes().toString());
		File mail = new File(dir, "mail");
		MailLocks.writeStoreSettings(FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()), Sharing.OFF);
		File users = new File(dir, "users.txt");
		Files.write(users.toPath(), "tony , secret , READ|WRITE\n".getBytes(StandardCharsets.UTF_8));

		for (String id : List.of("imap", "imaps")) {
			KnownSettings.RecordingProperties c = new KnownSettings.RecordingProperties();
			c.setProperty("parley.mail.root", mail.getPath());
			c.setProperty("parley.mail.users", users.getPath());
			MailServerProvider p = (MailServerProvider) ServerLauncher.providers().get(id);
			int port = freePort();
			c.setProperty(p.prefix() + ".port", "" + port);
			p.enabled(c);
			p.unavailable(c);
			p.warnings(c);
			p.port(c);
			if (!p.implicitTls()) {
				ServerMain.Service s = p.create(new ServerContext(), c);
				s.startAndWait(5000);
				try {
					session(port);
				} finally {
					s.stop(5000);
				}
			}
			assertEquals(List.of(), k.undeclared(c.asked()), id + " read these");
		}
	}

	private static void session(int port) throws Exception {
		try (Socket s = new Socket("127.0.0.1", port)) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			assertTrue(in.readLine().startsWith("* OK"));
			command(in, out, "a1 CAPABILITY");
			command(in, out, "a2 LOGIN tony secret");
			command(in, out, "a3 LIST \"\" \"*\"");
			out.write("a4 APPEND INBOX {7}\r\n".getBytes(StandardCharsets.US_ASCII));
			assertTrue(in.readLine().startsWith("+"));
			out.write("Hi: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			reply(in, "a4");
			command(in, out, "a5 SELECT INBOX");
			command(in, out, "a6 FETCH 1:* (FLAGS BODY.PEEK[HEADER])");
			command(in, out, "a7 NOOP");
			command(in, out, "a8 LOGOUT");
		}
	}

	private static void command(BufferedReader in, OutputStream out, String cmd) throws Exception {
		out.write((cmd + "\r\n").getBytes(StandardCharsets.US_ASCII));
		reply(in, cmd.substring(0, cmd.indexOf(' ')));
	}

	private static void reply(BufferedReader in, String tag) throws Exception {
		String line;
		while ((line = in.readLine()) != null && !line.startsWith(tag + " ")) {
			// untagged
		}
		assertTrue(line != null && line.startsWith(tag + " OK"), tag + ": " + line);
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
