package us.bringardner.parley.imap.test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal IMAP client for the tests: sends tagged commands on a raw socket and
 * collects the responses, with literals read inline.
 */
final class ImapClient implements Closeable {

	private static final Pattern LITERAL = Pattern.compile("~?\\{(\\d+)\\}$");

	private final int port;
	private Socket socket;
	private InputStream in;
	private OutputStream out;
	private int tagCount;
	final String greeting;

	ImapClient(int port) throws IOException {
		this.port = port;
		socket = new Socket("localhost", port);
		socket.setSoTimeout(60000);
		in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
		out = socket.getOutputStream();
		greeting = readLine();
	}

	byte[] readLineBytes() throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) >= 0 && b != '\n') {
			line.write(b);
		}
		if (b < 0 && line.size() == 0) {
			return null;
		}
		byte[] ret = line.toByteArray();
		if (ret.length > 0 && ret[ret.length - 1] == '\r') {
			ret = java.util.Arrays.copyOf(ret, ret.length - 1);
		}
		return ret;
	}

	String readLine() throws IOException {
		byte[] b = readLineBytes();
		return b == null ? null : new String(b, StandardCharsets.UTF_8);
	}

	/** One response: a line, with any literals (and the rest of the line) appended. */
	String readResponse() throws IOException {
		String line = readLine();
		if (line == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder(line);
		Matcher m = LITERAL.matcher(line);
		while (m.find()) {
			int n = Integer.parseInt(m.group(1));
			byte[] data = in.readNBytes(n);
			sb.append("\r\n").append(new String(data, StandardCharsets.UTF_8));
			String rest = readLine();
			sb.append(rest);
			m = LITERAL.matcher(rest);
		}
		return sb.toString();
	}

	String nextTag() {
		return "t" + (++tagCount);
	}

	void send(String text) throws IOException {
		sendBytes((text + "\r\n").getBytes(StandardCharsets.UTF_8));
	}

	void sendBytes(byte[] b) throws IOException {
		out.write(b);
		out.flush();
	}

	/** Send a command; the responses up to and including the tagged one. */
	List<String> cmd(String text) throws IOException {
		String tag = nextTag();
		send(tag + " " + text);
		return until(tag);
	}

	/** Read responses up to and including the one tagged {@code tag}. */
	List<String> until(String tag) throws IOException {
		List<String> ret = new ArrayList<>();
		String r;
		while ((r = readResponse()) != null) {
			ret.add(r);
			if (r.startsWith(tag + " ")) {
				break;
			}
		}
		return ret;
	}

	/** The tagged response (the last line) of a command. */
	static String last(List<String> responses) {
		return responses.get(responses.size() - 1);
	}

	/** Send a command and check that it completes with OK. */
	List<String> ok(String text) throws IOException {
		List<String> r = cmd(text);
		String tagged = last(r);
		if (!tagged.matches("t\\d+ OK.*")) {
			throw new AssertionError(text + " -> " + r);
		}
		return r;
	}

	/** APPEND with a synchronizing literal. */
	List<String> append(String mailbox, String flags, byte[] message) throws IOException {
		String tag = nextTag();
		send(tag + " APPEND " + mailbox + (flags == null ? "" : " " + flags) + " {" + message.length + "}");
		String cont = readLine();
		if (!cont.startsWith("+")) {
			List<String> r = new ArrayList<>();
			r.add(cont);
			return r;
		}
		sendBytes(message);
		send("");
		return until(tag);
	}

	void login(String user, String password) throws IOException {
		ok("LOGIN " + quote(user) + " " + quote(password));
	}

	static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** Fetch a literal of any size, returning its length and digest without keeping it. */
	long[] fetchDigest(String command, MessageDigest md) throws IOException {
		String tag = nextTag();
		send(tag + " " + command);
		long size = -1;
		String line;
		while ((line = readLine()) != null) {
			Matcher m = LITERAL.matcher(line);
			if (m.find()) {
				long n = Long.parseLong(m.group(1));
				size = n;
				byte[] buf = new byte[64 * 1024];
				while (n > 0) {
					int r = in.read(buf, 0, (int) Math.min(buf.length, n));
					if (r < 0) {
						throw new IOException("EOF in literal");
					}
					md.update(buf, 0, r);
					n -= r;
				}
				continue;
			}
			if (line.startsWith(tag + " ")) {
				break;
			}
		}
		return new long[] {size};
	}

	void startTls() throws Exception {
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, new TrustManager[] {new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		}}, null);
		SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(socket, "localhost", port, true);
		ssl.setUseClientMode(true);
		ssl.startHandshake();
		socket = ssl;
		in = new BufferedInputStream(ssl.getInputStream(), 64 * 1024);
		out = ssl.getOutputStream();
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
