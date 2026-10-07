package us.bringardner.parley.imap.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * An IMAP sequence set (RFC 9051 "sequence-set"): message sequence numbers or
 * UIDs such as {@code 1:4,7,10:*}, or "$" for the saved search result (SEARCHRES).
 */
public final class SequenceSet {

	/** "*": the largest number in use. */
	public static final long STAR = -1;

	private final List<long[]> ranges;
	private final boolean saved;

	private SequenceSet(List<long[]> ranges, boolean saved) {
		this.ranges = ranges;
		this.saved = saved;
	}

	/** True for a syntactically valid sequence set (or "$"). */
	public static boolean isSequenceSet(String s) {
		try {
			parse(s);
			return true;
		} catch (ImapParseException e) {
			return false;
		}
	}

	public static SequenceSet parse(String s) {
		if (s.equals("$")) {
			return new SequenceSet(List.of(), true);
		}
		List<long[]> ranges = new ArrayList<>();
		for (String part : s.split(",", -1)) {
			int colon = part.indexOf(':');
			if (colon < 0) {
				long v = number(part);
				ranges.add(new long[] {v, v});
			} else {
				ranges.add(new long[] {number(part.substring(0, colon)), number(part.substring(colon + 1))});
			}
		}
		return new SequenceSet(ranges, false);
	}

	private static long number(String s) {
		if (s.equals("*")) {
			return STAR;
		}
		if (s.isEmpty() || s.length() > 10 || s.charAt(0) == '0') {
			throw new ImapParseException("Invalid sequence set");
		}
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) < '0' || s.charAt(i) > '9') {
				throw new ImapParseException("Invalid sequence set");
			}
		}
		long v = Long.parseLong(s);
		if (v > 0xFFFFFFFFL) {
			throw new ImapParseException("Number too large in sequence set");
		}
		return v;
	}

	/** True for "$" (the saved search result). */
	public boolean isSaved() {
		return saved;
	}

	/** The largest explicit number in the set (for checking message numbers). */
	public long maxExplicit() {
		long max = 0;
		for (long[] r : ranges) {
			max = Math.max(max, Math.max(r[0], r[1]));
		}
		return max;
	}

	public boolean usesStar() {
		for (long[] r : ranges) {
			if (r[0] == STAR || r[1] == STAR) {
				return true;
			}
		}
		return false;
	}

	/** True if {@code value} is in the set, with "*" meaning {@code star}. */
	public boolean contains(long value, long star) {
		for (long[] r : ranges) {
			long a = r[0] == STAR ? star : r[0];
			long b = r[1] == STAR ? star : r[1];
			if (value >= Math.min(a, b) && value <= Math.max(a, b)) {
				return true;
			}
		}
		return false;
	}

	/** Format numbers compactly, e.g. 1,2,3,5 becomes "1:3,5". Empty for none. */
	public static String format(Collection<Long> values) {
		TreeSet<Long> sorted = new TreeSet<>(values);
		StringBuilder sb = new StringBuilder();
		Iterator<Long> it = sorted.iterator();
		while (it.hasNext()) {
			long start = it.next();
			long end = start;
			while (sorted.contains(end + 1)) {
				end = it.next();
			}
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(start);
			if (end != start) {
				sb.append(':').append(end);
			}
		}
		return sb.toString();
	}

	/** Format numbers in the given order (for COPYUID, whose two sets must correspond). */
	public static String formatOrdered(List<Long> values) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < values.size()) {
			long start = values.get(i);
			int j = i;
			while (j + 1 < values.size() && values.get(j + 1) == values.get(j) + 1) {
				j++;
			}
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(start);
			if (j > i) {
				sb.append(':').append(values.get(j));
			}
			i = j + 1;
		}
		return sb.toString();
	}
}
