package us.bringardner.parley.imap.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import us.bringardner.parley.files.FileSource;

/** A token of a parsed IMAP command: an atom, a quoted string, a literal or a parenthesized list. */
public abstract class ImapToken {

	/** The token as text (a literal is decoded as UTF-8; a list gives its contents). */
	public abstract String text();

	public boolean isAtom() {
		return this instanceof Atom;
	}

	/** True for a quoted string or a literal. */
	public boolean isString() {
		return this instanceof Quoted || this instanceof Literal;
	}

	public boolean isList() {
		return this instanceof ParenList;
	}

	/** True for the atom NIL. */
	public boolean isNil() {
		return this instanceof Atom && "NIL".equalsIgnoreCase(text());
	}

	public static final class Atom extends ImapToken {
		private final String value;

		public Atom(String value) {
			this.value = value;
		}

		@Override
		public String text() {
			return value;
		}

		@Override
		public String toString() {
			return value;
		}
	}

	public static final class Quoted extends ImapToken {
		private final String value;

		public Quoted(String value) {
			this.value = value;
		}

		@Override
		public String text() {
			return value;
		}

		@Override
		public String toString() {
			return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
	}

	/** A literal, held in memory or (when large) in a temp file. */
	public static final class Literal extends ImapToken {
		private final long length;
		private final byte[] data;
		private final FileSource file;
		private final boolean binary;

		public Literal(byte[] data, boolean binary) {
			this.length = data.length;
			this.data = data;
			this.file = null;
			this.binary = binary;
		}

		public Literal(FileSource file, long length, boolean binary) {
			this.length = length;
			this.data = null;
			this.file = file;
			this.binary = binary;
		}

		public long length() {
			return length;
		}

		/** True for a literal8 ("~{n}"). */
		public boolean isBinary() {
			return binary;
		}

		/** The temp file holding a large literal, or null if it is in memory. */
		public FileSource getFile() {
			return file;
		}

		public InputStream open() throws IOException {
			return data != null ? new ByteArrayInputStream(data) : file.getInputStream();
		}

		@Override
		public String text() {
			if (data != null) {
				return new String(data, StandardCharsets.UTF_8);
			}
			try (InputStream in = open()) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}

		@Override
		public String toString() {
			return "{" + length + "}";
		}
	}

	public static final class ParenList extends ImapToken {
		private final List<ImapToken> items;

		public ParenList(List<ImapToken> items) {
			this.items = items;
		}

		public List<ImapToken> items() {
			return items;
		}

		@Override
		public String text() {
			StringBuilder sb = new StringBuilder();
			for (ImapToken t : items) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(t.text());
			}
			return sb.toString();
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("(");
			for (ImapToken t : items) {
				if (sb.length() > 1) {
					sb.append(' ');
				}
				sb.append(t);
			}
			return sb.append(')').toString();
		}
	}
}
