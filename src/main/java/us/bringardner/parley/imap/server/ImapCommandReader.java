package us.bringardner.parley.imap.server;

import us.bringardner.parley.io.IoUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import us.bringardner.parley.files.FileSource;

/**
 * Reads one IMAP command (RFC 9051 section 9) from the connection: the tag, the
 * name and the arguments, which may span several lines joined by literals.
 * Synchronizing literals ("{n}") get a "+" continuation; non-synchronizing ones
 * ("{n+}", LITERAL+) don't. Literals larger than {@code memoryLimit} are streamed
 * into temp files, so an APPEND of any size never has to fit in memory.
 */
public final class ImapCommandReader {

	/** Creates temp files for large literals. */
	@FunctionalInterface
	public interface TempFiles {
		FileSource create() throws IOException;
	}

	/**
	 * A literal that is too large: for a synchronizing literal nothing more of the
	 * command was sent; a non-synchronizing one has been read and discarded.
	 */
	public static final class LiteralTooLargeException extends ImapParseException {
		private static final long serialVersionUID = 1L;

		LiteralTooLargeException(String tag, String message) {
			super(tag, message);
		}
	}

	private static final Pattern LITERAL = Pattern.compile("(~?)\\{(\\d{1,19})(\\+?)\\}$");

	private final ImapInput in;
	private final ImapOutput out;
	private final TempFiles temp;
	private int maxLine = 64 * 1024;
	private long memoryLimit = 1024 * 1024;
	private long literalLimit = 64 * 1024;
	private long appendLimit = Long.MAX_VALUE;

	public ImapCommandReader(ImapInput in, ImapOutput out, TempFiles temp) {
		this.in = in;
		this.out = out;
		this.temp = temp;
	}

	/** The largest literal accepted outside APPEND. */
	public void setLiteralLimit(long literalLimit) {
		this.literalLimit = literalLimit;
	}

	/** The largest message accepted by APPEND. */
	public void setAppendLimit(long appendLimit) {
		this.appendLimit = appendLimit;
	}

	public void setMaxLine(int maxLine) {
		this.maxLine = maxLine;
	}

	/**
	 * Read the next command, or null at end of stream.
	 *
	 * @throws ImapParseException for a syntax error (the whole command has been consumed)
	 */
	public ImapRequest read() throws IOException {
		byte[] line = in.readLine(maxLine);
		if (line == null) {
			return null;
		}
		List<FileSource> temps = new ArrayList<>();
		List<ImapToken> top = new ArrayList<>();
		Deque<List<ImapToken>> stack = new ArrayDeque<>();
		stack.push(top);
		StringBuilder text = new StringBuilder();
		String tag = null;
		try {
			while (true) {
				String s = new String(line, StandardCharsets.UTF_8);
				Matcher m = LITERAL.matcher(s);
				boolean literal = m.find();
				int end = literal ? lineOffsetOf(line, m.start()) : line.length;
				tokenize(line, end, stack);
				text.append(new String(line, 0, end, StandardCharsets.UTF_8));
				if (tag == null && !top.isEmpty()) {
					tag = top.get(0).text();
				}
				if (!literal) {
					break;
				}
				boolean binary = !m.group(1).isEmpty();
				boolean sync = m.group(3).isEmpty();
				long size;
				try {
					size = Long.parseLong(m.group(2));
				} catch (NumberFormatException e) {
					throw new ImapParseException(tag, "Invalid literal size");
				}
				boolean append = top.size() >= 2 && "APPEND".equalsIgnoreCase(top.get(1).text());
				long limit = append ? appendLimit : literalLimit;
				if (size > limit) {
					if (!sync) {
						in.readFully(size, null);
						skipRestOfCommand();
					}
					throw new LiteralTooLargeException(tag, append ? "[TOOBIG] Message too large" : "Literal too large");
				}
				if (sync) {
					out.line("+ Ready for literal data").flush();
				}
				ImapToken.Literal lit;
				if (size <= memoryLimit) {
					ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size);
					in.readFully(size, bytes);
					lit = new ImapToken.Literal(bytes.toByteArray(), binary);
				} else {
					FileSource f = temp.create();
					temps.add(f);
					try (OutputStream o = IoUtils.buffered(f.getOutputStream())) {
						in.readFully(size, o);
					}
					lit = new ImapToken.Literal(f, size, binary);
				}
				stack.peek().add(lit);
				text.append(lit);
				line = in.readLine(maxLine);
				if (line == null) {
					throw new java.io.EOFException("Connection closed in a command");
				}
			}
			if (stack.size() != 1) {
				throw new ImapParseException(tag, "Unbalanced parentheses");
			}
			if (top.size() < 2 || !top.get(0).isAtom() || !top.get(1).isAtom()) {
				throw new ImapParseException(tag, "Expected a tag and a command");
			}
			tag = top.get(0).text();
			if (tag.indexOf('+') >= 0 || tag.equals("*")) {
				throw new ImapParseException("*", "Invalid tag");
			}
			String name = top.get(1).text();
			String cmdText = text.toString();
			int sp = cmdText.indexOf(' ');
			return new ImapRequest(tag, name, new ArrayList<>(top.subList(2, top.size())),
					sp < 0 ? cmdText : cmdText.substring(sp + 1), temps);
		} catch (IOException | RuntimeException e) {
			for (FileSource f : temps) {
				try {
					f.delete();
				} catch (IOException ignore) {
					// best effort
				}
			}
			if (e instanceof ImapParseException && ((ImapParseException) e).getTag() == null && tag != null
					&& !(e instanceof LiteralTooLargeException)) {
				throw new ImapParseException(tag, e.getMessage());
			}
			throw e;
		}
	}

	/** After a rejected non-synchronizing literal: discard the rest of the command. */
	private void skipRestOfCommand() throws IOException {
		while (true) {
			byte[] line = in.readLine(maxLine);
			if (line == null) {
				return;
			}
			Matcher m = LITERAL.matcher(new String(line, StandardCharsets.UTF_8));
			if (!m.find() || m.group(3).isEmpty()) {
				return; // a synchronizing literal can't follow without our "+"
			}
			in.readFully(Long.parseLong(m.group(2)), null);
		}
	}

	private static int lineOffsetOf(byte[] line, int charIndex) {
		// the literal marker is ASCII at the end of the line: count back from the end
		String s = new String(line, StandardCharsets.UTF_8);
		return line.length - s.substring(charIndex).getBytes(StandardCharsets.UTF_8).length;
	}

	/** Parse the tokens of line[0..end) into the current list on the stack. */
	static void tokenize(byte[] line, int end, Deque<List<ImapToken>> stack) {
		int i = 0;
		while (i < end) {
			byte b = line[i];
			if (b == ' ') {
				i++;
			} else if (b == '(') {
				List<ImapToken> list = new ArrayList<>();
				stack.peek().add(new ImapToken.ParenList(list));
				stack.push(list);
				i++;
			} else if (b == ')') {
				if (stack.size() <= 1) {
					throw new ImapParseException("Unexpected )");
				}
				stack.pop();
				i++;
			} else if (b == '"') {
				ByteArrayOutputStream q = new ByteArrayOutputStream();
				i++;
				boolean closed = false;
				while (i < end) {
					byte c = line[i++];
					if (c == '\\' && i < end) {
						q.write(line[i++]);
					} else if (c == '"') {
						closed = true;
						break;
					} else {
						q.write(c);
					}
				}
				if (!closed) {
					throw new ImapParseException("Unterminated quoted string");
				}
				stack.peek().add(new ImapToken.Quoted(new String(q.toByteArray(), StandardCharsets.UTF_8)));
			} else if (b == '{') {
				throw new ImapParseException("A literal must end the line");
			} else if (b < 0x20 || b == 0x7f) {
				throw new ImapParseException("Control character in command");
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
				stack.peek().add(new ImapToken.Atom(new String(line, start, i - start, StandardCharsets.UTF_8)));
			}
		}
	}
}
