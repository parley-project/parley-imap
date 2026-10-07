package us.bringardner.parley.imap.client;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * One item of a server response (RFC 9051 section 9): an atom (including NIL and
 * numbers), a string (quoted or literal), or a parenthesized list. A literal that
 * was streamed to a caller's sink has no data here, only its length.
 */
public final class Token {

	public enum Kind {
		ATOM, STRING, LIST
	}

	private final Kind kind;
	private final String atom;
	private final byte[] data;
	private final long length;
	private final List<Token> items;

	private Token(Kind kind, String atom, byte[] data, long length, List<Token> items) {
		this.kind = kind;
		this.atom = atom;
		this.data = data;
		this.length = length;
		this.items = items;
	}

	static Token atom(String s) {
		return new Token(Kind.ATOM, s, null, s.length(), null);
	}

	static Token string(byte[] data) {
		return new Token(Kind.STRING, null, data, data.length, null);
	}

	/** A literal that went to a sink (e.g. a message body streamed to a file). */
	static Token streamed(long length) {
		return new Token(Kind.STRING, null, null, length, null);
	}

	static Token list(List<Token> items) {
		return new Token(Kind.LIST, null, null, items.size(), items);
	}

	public Kind getKind() {
		return kind;
	}

	public boolean isAtom() {
		return kind == Kind.ATOM;
	}

	public boolean isList() {
		return kind == Kind.LIST;
	}

	public boolean isString() {
		return kind == Kind.STRING;
	}

	/** True for the atom NIL. */
	public boolean isNil() {
		return kind == Kind.ATOM && "NIL".equalsIgnoreCase(atom);
	}

	/** True for a literal that was streamed to a sink instead of kept. */
	public boolean isStreamed() {
		return kind == Kind.STRING && data == null;
	}

	/** The text of an atom or string (UTF-8); null for NIL. */
	public String text() {
		if (kind == Kind.ATOM) {
			return isNil() ? null : atom;
		}
		if (kind == Kind.STRING) {
			return data == null ? null : new String(data, StandardCharsets.UTF_8);
		}
		throw new IllegalStateException("A list has no text");
	}

	/** The bytes of a string. */
	public byte[] bytes() {
		return data;
	}

	/** The length of a string (also of a streamed literal). */
	public long length() {
		return length;
	}

	public List<Token> items() {
		return items == null ? Collections.emptyList() : items;
	}

	/** The number value of an atom. */
	public long number() {
		try {
			return Long.parseLong(atom);
		} catch (NumberFormatException | NullPointerException e) {
			throw new ImapProtocolException("Expected a number, got " + this);
		}
	}

	@Override
	public String toString() {
		switch (kind) {
		case ATOM:
			return atom;
		case STRING:
			return data == null ? "{" + length + " streamed}" : "\"" + text() + "\"";
		default:
			StringBuilder sb = new StringBuilder("(");
			for (Token t : items) {
				if (sb.length() > 1) {
					sb.append(' ');
				}
				sb.append(t);
			}
			return sb.append(')').toString();
		}
	}
}
