package us.bringardner.parley.imap.server;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Locale;

/**
 * Formatting of IMAP strings and dates (RFC 9051 section 4.3 and 9).
 */
public final class ImapStrings {

	private static final int MAX_QUOTED = 1000;
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss Z", Locale.US);
	private static final DateTimeFormatter PARSE_DATE_TIME = new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendPattern("d-MMM-yyyy HH:mm:ss Z").toFormatter(Locale.US);
	private static final DateTimeFormatter PARSE_DATE = new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendPattern("d-MMM-yyyy").toFormatter(Locale.US);

	private ImapStrings() {
	}

	/**
	 * A string as a quoted string or, when it can't be quoted, a literal ("{n}"
	 * CRLF and the bytes). Non-ASCII text is quoted only in UTF-8 sessions
	 * (IMAP4rev2 or UTF8=ACCEPT); NUL can't be sent and is dropped.
	 */
	public static String string(String s, boolean utf8) {
		if (s.indexOf('\0') >= 0) {
			s = s.replace("\0", "");
		}
		boolean literal = s.length() > MAX_QUOTED;
		for (int i = 0; i < s.length() && !literal; i++) {
			char c = s.charAt(i);
			literal = c == '\r' || c == '\n' || (c >= 0x80 && !utf8);
		}
		if (literal) {
			return "{" + s.getBytes(StandardCharsets.UTF_8).length + "}\r\n" + s;
		}
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** A string, or NIL for null. */
	public static String nstring(String s, boolean utf8) {
		return s == null ? "NIL" : string(s, utf8);
	}

	/** An astring: an atom when possible (for mailbox names and similar). */
	public static String astring(String s, boolean utf8) {
		if (!s.isEmpty() && isAtom(s)) {
			return s;
		}
		return string(s, utf8);
	}

	/** True if every character is an ATOM-CHAR (or "]", allowed in astrings). */
	public static boolean isAtom(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c <= 0x20 || c >= 0x7f || "(){%*\"\\".indexOf(c) >= 0) {
				return false;
			}
		}
		return !s.isEmpty() && !s.equalsIgnoreCase("NIL");
	}

	/** True if the text is a valid flag keyword (atom without "]"). */
	public static boolean isKeyword(String s) {
		return isAtom(s) && s.indexOf(']') < 0;
	}

	/** "(a b c)". */
	public static String list(Collection<String> items) {
		return "(" + String.join(" ", items) + ")";
	}

	/** INTERNALDATE: "02-Oct-2026 15:00:00 -0400" (date-time, RFC 9051). */
	public static String dateTime(long millis) {
		return "\"" + DATE_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())) + "\"";
	}

	/** Parse an APPEND date-time ("d-MMM-yyyy HH:mm:ss +zzzz", the day may be space padded); -1 if invalid. */
	public static long parseDateTime(String s) {
		try {
			return ZonedDateTime.parse(s.trim(), PARSE_DATE_TIME).toInstant().toEpochMilli();
		} catch (DateTimeParseException e) {
			return -1;
		}
	}

	/** Parse a SEARCH date ("1-Feb-1994"); null if invalid. */
	public static LocalDate parseDate(String s) {
		try {
			return LocalDate.parse(s.trim(), PARSE_DATE);
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
