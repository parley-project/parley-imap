package us.bringardner.parley.imap.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Reads the client side of an IMAP connection: command lines (bytes up to CRLF)
 * and literals (exactly n bytes, streamed). A line interrupted by a socket timeout
 * is kept, so reading can resume (used while IDLE waits for "DONE").
 */
public final class ImapInput {

	/** A line longer than the limit; the rest of the line has been discarded. */
	public static final class LineTooLongException extends IOException {
		private static final long serialVersionUID = 1L;

		LineTooLongException(int max) {
			super("Line longer than " + max + " bytes");
		}
	}

	private final InputStream in;
	private final byte[] buf = new byte[64 * 1024];
	private int pos;
	private int len;
	private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
	private boolean discarding;
	private volatile BooleanSupplier keepWaiting;
	private volatile long lastReceived = System.currentTimeMillis();

	public ImapInput(InputStream in) {
		this.in = in;
	}

	/**
	 * Asked after a socket read timeout: true to keep waiting, false to let the
	 * {@link java.net.SocketTimeoutException} through (reading can resume later).
	 */
	public void setKeepWaiting(BooleanSupplier keepWaiting) {
		this.keepWaiting = keepWaiting;
	}

	/** The time (ms) bytes were last received. */
	public long getLastReceived() {
		return lastReceived;
	}

	private boolean fill() throws IOException {
		if (pos < len) {
			return true;
		}
		int n;
		while (true) {
			try {
				n = in.read(buf, 0, buf.length);
				break;
			} catch (SocketTimeoutException e) {
				BooleanSupplier k = keepWaiting;
				if (k == null || !k.getAsBoolean()) {
					throw e;
				}
			}
		}
		lastReceived = System.currentTimeMillis();
		if (n <= 0) {
			return false;
		}
		pos = 0;
		len = n;
		return true;
	}

	/**
	 * Read a line without its CRLF (or LF). Returns null at end of stream.
	 *
	 * @throws LineTooLongException if the line is longer than {@code max} bytes
	 */
	public byte[] readLine(int max) throws IOException {
		while (true) {
			if (!fill()) {
				return null;
			}
			int i = pos;
			while (i < len && buf[i] != '\n') {
				i++;
			}
			if (!discarding) {
				partial.write(buf, pos, i - pos);
			}
			boolean found = i < len;
			pos = found ? i + 1 : len;
			if (!discarding && partial.size() > max) {
				discarding = true;
				partial.reset();
			}
			if (found) {
				if (discarding) {
					discarding = false;
					throw new LineTooLongException(max);
				}
				byte[] line = partial.toByteArray();
				partial.reset();
				int n = line.length;
				if (n > 0 && line[n - 1] == '\r') {
					n--;
				}
				return n == line.length ? line : java.util.Arrays.copyOf(line, n);
			}
		}
	}

	/** Copy exactly {@code n} bytes to {@code out}. */
	public void readFully(long n, OutputStream out) throws IOException {
		while (n > 0) {
			if (!fill()) {
				throw new EOFException("Connection closed in a literal");
			}
			int c = (int) Math.min(n, len - pos);
			if (out != null) {
				out.write(buf, pos, c);
			}
			pos += c;
			n -= c;
		}
	}

	/** True if bytes have been received but not read yet. */
	public boolean hasBuffered() {
		return pos < len || partial.size() > 0;
	}

	/** Forget anything received but not read (after STARTTLS, RFC 9051 section 11.1). */
	public void discardBuffered() {
		pos = len = 0;
		partial.reset();
		discarding = false;
	}
}
