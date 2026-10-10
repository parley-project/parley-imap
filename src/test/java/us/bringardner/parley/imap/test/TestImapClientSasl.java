package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.imap.client.ImapClient;
import us.bringardner.parley.imap.client.ImapClientConfig;
import us.bringardner.parley.imap.client.ImapClientConfig.Security;
import us.bringardner.parley.net.sasl.ISaslAuthenticator;
import us.bringardner.parley.net.sasl.ISaslChannel;
import us.bringardner.parley.net.sasl.ISaslServer;
import us.bringardner.parley.net.sasl.SaslEncoding;
import us.bringardner.parley.net.sasl.SaslOutcome;
import us.bringardner.parley.net.sasl.SaslServerDriver;
import us.bringardner.parley.net.sasl.SaslStep;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.sasl.ScramMechanism;

/**
 * The client's SASL login against a small scripted server that runs the real SCRAM server side.
 */
public class TestImapClientSasl {

	private static final String USER = "alice";
	private static final String PASSWORD = "s3cret";
	private static final String CAPS = "IMAP4rev1 AUTH=SCRAM-SHA-256 AUTH=PLAIN SASL-IR";

	private ServerSocket listener;
	private final List<String> mechanisms = new CopyOnWriteArrayList<>();

	@AfterEach
	public void stop() throws IOException {
		if (listener != null) {
			listener.close();
		}
	}

	/** A server that has SCRAM credentials for one user. */
	private static ISaslAuthenticator users() {
		final ScramCredentials stored = ScramMechanism.SHA_256.deriveCredentials(PASSWORD,
				"0123456789abcdef".getBytes(StandardCharsets.US_ASCII), 4096);
		return new ISaslAuthenticator() {
			@Override
			public boolean checkPassword(String user, String password) {
				return USER.equals(user) && PASSWORD.equals(password);
			}

			@Override
			public ScramCredentials getScramCredentials(String user, String hash) {
				return USER.equals(user) && "SHA-256".equals(hash) ? stored : null;
			}
		};
	}

	/**
	 * Start the server on a free port.
	 *
	 * @param honest false: after the client's proof it says OK without proving its own
	 */
	private int serve(boolean honest) throws IOException {
		listener = new ServerSocket(0);
		Thread t = new Thread(() -> {
			try (Socket s = listener.accept()) {
				session(s, honest);
			} catch (IOException e) {
				// the test closed the listener or the client went away
			}
		});
		t.setDaemon(true);
		t.start();
		return listener.getLocalPort();
	}

	private void session(Socket s, boolean honest) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
		Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
		out.write("* OK [CAPABILITY " + CAPS + "] ready\r\n");
		out.flush();
		ISaslAuthenticator users = users();
		String line;
		while ((line = in.readLine()) != null) {
			String[] w = line.split(" ", 4);
			String tag = w[0];
			String cmd = w.length > 1 ? w[1].toUpperCase() : "";
			if (cmd.equals("CAPABILITY")) {
				out.write("* CAPABILITY " + CAPS + "\r\n" + tag + " OK done\r\n");
			} else if (cmd.equals("AUTHENTICATE")) {
				String mech = w[2].toUpperCase();
				String ir = w.length > 3 ? w[3] : null;
				mechanisms.add(mech);
				boolean ok;
				if (mech.equals("SCRAM-SHA-256") && !honest) {
					ok = dishonestScram(users, ir, in, out);
				} else {
					out.flush();
					ISaslChannel channel = new ISaslChannel() {
						@Override
						public void sendChallenge(String base64) throws IOException {
							out.write("+ " + base64 + "\r\n");
							out.flush();
						}

						@Override
						public String readResponse() throws IOException {
							return in.readLine();
						}
					};
					SaslOutcome o = SaslServerDriver.authenticate(
							mech.equals("PLAIN") ? new us.bringardner.parley.net.sasl.PlainMechanism() : ScramMechanism.SHA_256,
							users, ir, channel);
					ok = o.isSuccess();
				}
				out.write(tag + (ok ? " OK [CAPABILITY IMAP4rev1] Logged in\r\n"
						: " NO [AUTHENTICATIONFAILED] Invalid credentials\r\n"));
			} else if (cmd.equals("LOGOUT")) {
				out.write("* BYE bye\r\n" + tag + " OK done\r\n");
				out.flush();
				return;
			} else {
				out.write(tag + " OK done\r\n");
			}
			out.flush();
		}
	}

	/** Gets to the end of the exchange but never sends the server's proof. */
	private static boolean dishonestScram(ISaslAuthenticator users, String ir, BufferedReader in, Writer out)
			throws IOException {
		ISaslServer server = ScramMechanism.SHA_256.newServer(users);
		SaslStep first = server.start(SaslEncoding.decode(ir));
		if (first.getKind() != SaslStep.Kind.CHALLENGE) {
			return false;
		}
		out.write("+ " + SaslEncoding.encode(first.getData()) + "\r\n");
		out.flush();
		in.readLine(); // the client's proof; accepted without a look
		return true;
	}

	private ImapClient client(int port, List<String> wanted) {
		return new ImapClient(new ImapClientConfig("localhost", Security.NONE).setPort(port).setReadTimeout(10000)
				.setAuthMechanisms(wanted));
	}

	@Test
	public void scramLoginNeverSendsThePassword() throws Exception {
		int port = serve(true);
		try (ImapClient c = client(port, Arrays.asList("SCRAM-SHA-256", "PLAIN"))) {
			c.connect();
			c.login(USER, PASSWORD);
			assertEquals(Collections.singletonList("SCRAM-SHA-256"), mechanisms);
			assertEquals(ImapClient.State.AUTHENTICATED, c.getState());
		}
	}

	@Test
	public void aWrongPasswordFailsAndIsNotRetriedWithPlain() throws Exception {
		int port = serve(true);
		try (ImapClient c = client(port, Arrays.asList("SCRAM-SHA-256", "PLAIN"))) {
			c.connect();
			assertThrows(IOException.class, () -> c.login(USER, "wrong"));
			assertEquals(Collections.singletonList("SCRAM-SHA-256"), mechanisms);
		}
	}

	@Test
	public void aServerThatSkipsItsProofIsRefused() throws Exception {
		int port = serve(false);
		try (ImapClient c = client(port, Arrays.asList("SCRAM-SHA-256"))) {
			c.connect();
			IOException e = assertThrows(IOException.class, () -> c.login(USER, PASSWORD));
			assertTrue(e.getMessage().contains("SCRAM-SHA-256"), e.getMessage());
			assertEquals(ImapClient.State.DISCONNECTED, c.getState());
		}
	}

	@Test
	public void theOrderOfThePreferenceListDecides() throws Exception {
		int port = serve(true);
		try (ImapClient c = client(port, Arrays.asList("PLAIN", "SCRAM-SHA-256"))) {
			c.connect();
			c.login(USER, PASSWORD);
			assertEquals(Collections.singletonList("PLAIN"), mechanisms);
		}
	}

	@Test
	public void withoutAPreferenceListLoginIsPlainAsBefore() throws Exception {
		int port = serve(true);
		try (ImapClient c = client(port, Collections.<String>emptyList())) {
			c.connect();
			c.login(USER, PASSWORD);
			assertEquals(Collections.singletonList("PLAIN"), mechanisms);
		}
	}

	@Test
	public void mechanismsTheServerLacksAreSkipped() throws Exception {
		int port = serve(true);
		try (ImapClient c = client(port, Arrays.asList("SCRAM-SHA-1", "CRAM-MD5", "SCRAM-SHA-256"))) {
			c.connect();
			assertTrue(c.getCapabilitySet().has("AUTH", "SCRAM-SHA-256"));
			assertFalse(c.getCapabilitySet().has("AUTH", "CRAM-MD5"));
			c.login(USER, PASSWORD);
			assertEquals(Collections.singletonList("SCRAM-SHA-256"), mechanisms);
		}
	}
}
