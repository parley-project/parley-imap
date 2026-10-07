package us.bringardner.parley.imap.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import us.bringardner.parley.imap.server.ImapInput;

/**
 * Reads server responses (RFC 9051 section 9) from the connection. Literals are
 * read as exact byte counts; a literal can be handed to a {@link LiteralSink}
 * (e.g. a file) so a message of any size is never held in memory.
 */
final class ResponseReader {

	/** Where large literals go; return null to keep a literal in memory. */
	@FunctionalInterface
	interface LiteralSink {
		OutputStream open(long size) throws IOException;
	}

	private static final Set<String> STATUS = Set.of("OK", "NO", "BAD", "BYE", "PREAUTH");
	private static final int MAX_LINE = 16 * 1024 * 1024;

	private final ImapInput in;
	private long maxMemoryLiteral = 64L * 1024 * 1024;
	private volatile LiteralSink sink;

	ResponseReader(ImapInput in) {
		this.in = in;
	}

	ImapInput input() {
		return in;
	}

	void setSink(LiteralSink sink) {
		this.sink = sink;
	}

	void setMaxMemoryLiteral(long max) {
		this.maxMemoryLiteral = max;
	}

	/** The next response, or null at end of stream. */
	Response read() throws IOException {
		byte[] line = in.readLine(MAX_LINE);
		if (line == null) {
			return null;
		}
		Response r = new Response();
		String s = new String(line, StandardCharsets.UTF_8);
		if (s.startsWith("+")) {
			r.tag = "+";
			r.text = s.length() > 1 ? s.substring(s.charAt(1) == ' ' ? 2 : 1) : "";
			return r;
		}
		int sp = s.indexOf(' ');
		if (sp <= 0) {
			throw new ImapProtocolException("Invalid response: " + abbreviate(s));
		}
		r.tag = s.substring(0, sp);
		String rest = s.substring(sp + 1);
		int sp2 = rest.indexOf(' ');
		String word = (sp2 < 0 ? rest : rest.substring(0, sp2)).toUpperCase(Locale.ROOT);
		if (STATUS.contains(word)) {
			r.status = word;
			String after = sp2 < 0 ? "" : rest.substring(sp2 + 1);
			if (after.startsWith("[")) {
				int end = closingBracket(after);
				if (end > 0) {
					r.code = after.substring(1, end);
					after = after.substring(end + 1).trim();
				}
			}
			r.text = after;
			return r;
		}
		// untagged data, possibly spanning literals
		Deque<List<Token>> stack = new ArrayDeque<>();
		stack.push(r.tokens);
		int offset = sp + 1;
		while (true) {
			int literalStart = literalMarker(line);
			int end = literalStart < 0 ? line.length : literalStart;
			tokenize(line, offset, end, stack);
			if (literalStart < 0) {
				break;
			}
			int open = line[literalStart] == '~' ? literalStart + 1 : literalStart;
			long size = Long.parseLong(new String(line, open + 1, line.length - open - 2, StandardCharsets.US_ASCII));
			stack.peek().add(readLiteral(size));
			line = in.readLine(MAX_LINE);
			if (line == null) {
				throw new java.io.EOFException("Connection closed in a response");
			}
			offset = 0;
		}
		return r;
	}

	private Token readLiteral(long size) throws IOException {
		LiteralSink k = sink;
		OutputStream target = k == null ? null : k.open(size);
		if (target != null) {
			in.readFully(size, target);
			target.flush();
			return Token.streamed(size);
		}
		if (size > maxMemoryLiteral) {
			in.readFully(size, null);
			throw new ImapProtocolException("A " + size + "-byte literal is too large to keep in memory");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size);
		in.readFully(size, bytes);
		return Token.string(bytes.toByteArray());
	}

	/** The start of a "{n}" or "~{n}" marker that ends the line, or -1. */
	static int literalMarker(byte[] line) {
		int n = line.length;
		if (n < 3 || line[n - 1] != '}') {
			return -1;
		}
		int i = n - 2;
		while (i >= 0 && line[i] >= '0' && line[i] <= '9') {
			i--;
		}
		if (i < 0 || line[i] != '{' || i == n - 2) {
			return -1;
		}
		return i > 0 && line[i - 1] == '~' ? i - 1 : i;
	}

	private static int closingBracket(String s) {
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (quoted) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					quoted = false;
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == '[') {
				depth++;
			} else if (c == ']' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** Tokens of line[from..end) into the list on top of the stack (lenient). */
	static void tokenize(byte[] line, int from, int end, Deque<List<Token>> stack) {
		int i = from;
		while (i < end) {
			byte b = line[i];
			if (b == ' ') {
				i++;
			} else if (b == '(') {
				List<Token> list = new ArrayList<>();
				stack.peek().add(Token.list(list));
				stack.push(list);
				i++;
			} else if (b == ')') {
				if (stack.size() > 1) {
					stack.pop();
				}
				i++;
			} else if (b == '"') {
				ByteArrayOutputStream q = new ByteArrayOutputStream();
				i++;
				while (i < end) {
					byte c = line[i++];
					if (c == '\\' && i < end) {
						q.write(line[i++]);
					} else if (c == '"') {
						break;
					} else {
						q.write(c);
					}
				}
				stack.peek().add(Token.string(q.toByteArray()));
			} else {
				int start = i;
				int depth = 0;
				while (i < end) {
					byte c = line[i];
					if (c == '[') {
						depth++;
					} else if (c == ']' && depth > 0) {
						depth--;
					} else if (depth == 0 && (c == ' ' || c == '(' || c == ')' || c == '"')) {
						break;
					}
					i++;
				}
				stack.peek().add(Token.atom(new String(line, start, i - start, StandardCharsets.UTF_8)));
			}
		}
	}

	private static String abbreviate(String s) {
		return s.length() > 80 ? s.substring(0, 80) + "..." : s;
	}
}
