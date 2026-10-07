package us.bringardner.parley.imap.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.imap.server.ModifiedUtf7;

/**
 * A command to send: a name and arguments. Strings are sent quoted when
 * possible and as literals otherwise; mailbox names are encoded for the session
 * (UTF-8, or modified UTF-7 for IMAP4rev1 servers without UTF8=ACCEPT).
 */
public final class Command {

	/** A literal argument. */
	static final class Literal {
		final InputStream data;
		final long size;
		final boolean binary;
		final ProgressListener progress;

		Literal(InputStream data, long size, boolean binary, ProgressListener progress) {
			this.data = data;
			this.size = size;
			this.binary = binary;
			this.progress = progress;
		}
	}

	final String name;
	final List<Object> parts = new ArrayList<>();
	/** Text for the protocol trace (secrets replaced). */
	private final StringBuilder display = new StringBuilder();
	private final boolean utf8;

	Command(String name, boolean utf8) {
		this.name = name;
		this.utf8 = utf8;
		display.append(name);
	}

	/** Raw text, sent as is (atoms, sequence sets, parenthesized lists). */
	public Command raw(String text) {
		parts.add(text);
		display.append(' ').append(text);
		return this;
	}

	/** A string: quoted if it can be, else a literal. */
	public Command string(String s) {
		return string(s, false);
	}

	/** A password or other secret: sent like {@link #string(String)}, shown as "***" in traces. */
	public Command secret(String s) {
		return string(s, true);
	}

	private Command string(String s, boolean secret) {
		boolean quote = s.length() <= 1000;
		for (int i = 0; i < s.length() && quote; i++) {
			char c = s.charAt(i);
			quote = c != '\r' && c != '\n' && c != 0 && (c < 0x80 || utf8);
		}
		if (quote) {
			parts.add("\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
			display.append(' ').append(secret ? "***" : parts.get(parts.size() - 1));
		} else {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			parts.add(new Literal(new ByteArrayInputStream(b), b.length, false, null));
			display.append(' ').append(secret ? "***" : "{" + b.length + "}");
		}
		return this;
	}

	/** A mailbox name, encoded for the session. */
	public Command mailbox(String name) {
		return string(utf8 ? name : ModifiedUtf7.encode(name));
	}

	/** A literal read from a stream (APPEND). */
	public Command literal(InputStream data, long size, boolean binary, ProgressListener progress) {
		parts.add(new Literal(data, size, binary, progress));
		display.append(' ').append(binary ? "~" : "").append('{').append(size).append('}');
		return this;
	}

	@Override
	public String toString() {
		return display.toString();
	}
}
