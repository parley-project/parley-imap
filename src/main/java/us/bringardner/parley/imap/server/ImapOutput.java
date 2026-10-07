package us.bringardner.parley.imap.server;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes the server side of an IMAP connection. Output is buffered; responses
 * are sent by {@link #flush()}. Only the session's own thread writes.
 */
public final class ImapOutput {

	private static final byte[] CRLF = {'\r', '\n'};

	private final OutputStream out;

	public ImapOutput(OutputStream socketOut) {
		this.out = new BufferedOutputStream(socketOut, 64 * 1024);
	}

	public ImapOutput write(String s) throws IOException {
		out.write(s.getBytes(StandardCharsets.UTF_8));
		return this;
	}

	public ImapOutput write(byte[] b) throws IOException {
		out.write(b);
		return this;
	}

	public ImapOutput crlf() throws IOException {
		out.write(CRLF);
		return this;
	}

	/** A whole response line. */
	public ImapOutput line(String s) throws IOException {
		return write(s).crlf();
	}

	/** A literal: "{n}" CRLF and the bytes (or "~{n}" for a literal8). */
	public ImapOutput literal(byte[] data, boolean literal8) throws IOException {
		write((literal8 ? "~{" : "{") + data.length + "}").crlf();
		out.write(data);
		return this;
	}

	/** A literal of {@code length} bytes copied from a stream. */
	public ImapOutput literal(long length, InputStream data, boolean literal8) throws IOException {
		write((literal8 ? "~{" : "{") + length + "}").crlf();
		byte[] buf = new byte[64 * 1024];
		long left = length;
		while (left > 0) {
			int n = data.read(buf, 0, (int) Math.min(buf.length, left));
			if (n <= 0) {
				// the data shrank: pad so the protocol stays in sync
				while (left-- > 0) {
					out.write(' ');
				}
				break;
			}
			out.write(buf, 0, n);
			left -= n;
		}
		return this;
	}

	/** The underlying (buffered) stream, for writing literal content directly. */
	public OutputStream stream() {
		return out;
	}

	public void flush() throws IOException {
		out.flush();
	}
}
