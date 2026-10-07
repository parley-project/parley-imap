package us.bringardner.parley.imap.client;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SEARCH criteria built in code (RFC 9051 section 6.4.4), so strings are quoted
 * or sent as literals correctly:
 * <pre>
 * SearchCriteria.and(SearchCriteria.unseen(), SearchCriteria.from("tony"), SearchCriteria.since(LocalDate.of(2026, 10, 1)))
 * </pre>
 */
public final class SearchCriteria {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.US);

	/** Raw text or a string argument. */
	private final List<Object> parts = new ArrayList<>();

	private static final class Str {
		final String value;

		Str(String value) {
			this.value = value;
		}
	}

	private SearchCriteria() {
	}

	private static SearchCriteria raw(String text) {
		SearchCriteria c = new SearchCriteria();
		c.parts.add(text);
		return c;
	}

	private static SearchCriteria keyString(String key, String value) {
		SearchCriteria c = raw(key);
		c.parts.add(new Str(value));
		return c;
	}

	public static SearchCriteria all() {
		return raw("ALL");
	}

	public static SearchCriteria seen() {
		return raw("SEEN");
	}

	public static SearchCriteria unseen() {
		return raw("UNSEEN");
	}

	public static SearchCriteria flagged() {
		return raw("FLAGGED");
	}

	public static SearchCriteria deleted() {
		return raw("DELETED");
	}

	public static SearchCriteria answered() {
		return raw("ANSWERED");
	}

	public static SearchCriteria keyword(String keyword) {
		return raw("KEYWORD " + keyword);
	}

	public static SearchCriteria from(String text) {
		return keyString("FROM", text);
	}

	public static SearchCriteria to(String text) {
		return keyString("TO", text);
	}

	public static SearchCriteria cc(String text) {
		return keyString("CC", text);
	}

	public static SearchCriteria subject(String text) {
		return keyString("SUBJECT", text);
	}

	public static SearchCriteria body(String text) {
		return keyString("BODY", text);
	}

	/** Header and body text. */
	public static SearchCriteria text(String text) {
		return keyString("TEXT", text);
	}

	public static SearchCriteria header(String field, String text) {
		SearchCriteria c = keyString("HEADER", field);
		c.parts.add(new Str(text));
		return c;
	}

	/** Received on or after the date. */
	public static SearchCriteria since(LocalDate date) {
		return raw("SINCE " + DATE.format(date));
	}

	/** Received before the date. */
	public static SearchCriteria before(LocalDate date) {
		return raw("BEFORE " + DATE.format(date));
	}

	public static SearchCriteria sentSince(LocalDate date) {
		return raw("SENTSINCE " + DATE.format(date));
	}

	public static SearchCriteria larger(long bytes) {
		return raw("LARGER " + bytes);
	}

	public static SearchCriteria smaller(long bytes) {
		return raw("SMALLER " + bytes);
	}

	public static SearchCriteria uid(String uidSet) {
		return raw("UID " + uidSet);
	}

	public static SearchCriteria not(SearchCriteria c) {
		SearchCriteria r = raw("NOT");
		r.parts.add(c);
		return r;
	}

	public static SearchCriteria or(SearchCriteria a, SearchCriteria b) {
		SearchCriteria r = raw("OR");
		r.parts.add(a);
		r.parts.add(b);
		return r;
	}

	/** All of the criteria must match. */
	public static SearchCriteria and(SearchCriteria... all) {
		SearchCriteria r = new SearchCriteria();
		r.parts.add("(");
		r.parts.addAll(Arrays.asList(all));
		r.parts.add(")");
		return r;
	}

	/** True if a string argument isn't ASCII (CHARSET UTF-8 is then needed for IMAP4rev1). */
	boolean needsUtf8() {
		for (Object p : parts) {
			if (p instanceof Str && !((Str) p).value.chars().allMatch(ch -> ch < 0x80)) {
				return true;
			}
			if (p instanceof SearchCriteria && ((SearchCriteria) p).needsUtf8()) {
				return true;
			}
		}
		return false;
	}

	void appendTo(Command c) {
		for (Object p : parts) {
			if (p instanceof String) {
				c.raw((String) p);
			} else if (p instanceof Str) {
				c.string(((Str) p).value);
			} else {
				((SearchCriteria) p).appendTo(c);
			}
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Object p : parts) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(p instanceof Str ? "\"" + ((Str) p).value + "\"" : p.toString());
		}
		return sb.toString();
	}
}
