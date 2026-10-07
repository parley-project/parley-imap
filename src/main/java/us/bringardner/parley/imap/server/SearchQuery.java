package us.bringardner.parley.imap.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import us.bringardner.parley.mail.EncodedWord;
import us.bringardner.parley.mail.Header;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.mail.Rfc2822Date;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MessageInfo;

/**
 * The search criteria of SEARCH (RFC 9051 section 6.4.4). Text criteria match
 * case-insensitively; header text is matched after decoding RFC 2047 encoded
 * words, and BODY and TEXT search the decoded text of the text parts.
 */
public final class SearchQuery {

	/** The message being tested. Its file is parsed only if a criterion needs it. */
	static final class Ctx {
		final MessageInfo info;
		final Mailbox mailbox;
		final int msn;
		final int maxMsn;
		final long maxUid;
		final Set<Long> saved;
		Set<String> flags;
		Message message;

		Ctx(MessageInfo info, Mailbox mailbox, int msn, int maxMsn, long maxUid, Set<Long> saved) {
			this.info = info;
			this.mailbox = mailbox;
			this.msn = msn;
			this.maxMsn = maxMsn;
			this.maxUid = maxUid;
			this.saved = saved;
		}

		Set<String> flags() {
			if (flags == null) {
				flags = info.getFlags(mailbox);
			}
			return flags;
		}

		Message message() throws IOException {
			if (message == null) {
				message = Message.parse(info.getFile());
			}
			return message;
		}

		void close() throws IOException {
			if (message != null) {
				message.close();
				message = null;
			}
		}
	}

	@FunctionalInterface
	interface Key {
		boolean matches(Ctx c) throws IOException;
	}

	private final Key key;

	private SearchQuery(Key key) {
		this.key = key;
	}

	/** Parse the criteria: every remaining argument of the request (all must match). */
	public static SearchQuery parse(ImapRequest req) {
		List<Key> keys = new ArrayList<>();
		while (req.hasNext()) {
			keys.add(parseKey(req));
		}
		if (keys.isEmpty()) {
			throw new ImapParseException("Missing search criteria");
		}
		return new SearchQuery(and(keys));
	}

	boolean matches(Ctx c) throws IOException {
		return key.matches(c);
	}

	private static Key and(List<Key> keys) {
		if (keys.size() == 1) {
			return keys.get(0);
		}
		return c -> {
			for (Key k : keys) {
				if (!k.matches(c)) {
					return false;
				}
			}
			return true;
		};
	}

	/** A cursor over a parenthesized list, so lists parse like the top level. */
	private static ImapRequest sub(ImapToken.ParenList list) {
		return new ImapRequest("*", "SEARCH", new ArrayList<>(list.items()), "", List.of());
	}

	private static Key parseKey(ImapRequest req) {
		ImapToken t = req.next();
		if (t.isList()) {
			ImapRequest r = sub((ImapToken.ParenList) t);
			List<Key> keys = new ArrayList<>();
			while (r.hasNext()) {
				keys.add(parseKey(r));
			}
			if (keys.isEmpty()) {
				throw new ImapParseException("Empty search list");
			}
			return and(keys);
		}
		if (!t.isAtom()) {
			throw new ImapParseException("Invalid search key");
		}
		String word = t.text();
		if (SequenceSet.isSequenceSet(word)) {
			SequenceSet set = SequenceSet.parse(word);
			if (set.isSaved()) {
				return c -> c.saved.contains(c.info.getUid());
			}
			return c -> set.contains(c.msn, c.maxMsn);
		}
		switch (word.toUpperCase(Locale.ROOT)) {
		case "ALL":
			return c -> true;
		case "ANSWERED":
			return flag(IMAP.ANSWERED, true);
		case "UNANSWERED":
			return flag(IMAP.ANSWERED, false);
		case "DELETED":
			return flag(IMAP.DELETED, true);
		case "UNDELETED":
			return flag(IMAP.DELETED, false);
		case "DRAFT":
			return flag(IMAP.DRAFT, true);
		case "UNDRAFT":
			return flag(IMAP.DRAFT, false);
		case "FLAGGED":
			return flag(IMAP.FLAGGED, true);
		case "UNFLAGGED":
			return flag(IMAP.FLAGGED, false);
		case "SEEN":
			return flag(IMAP.SEEN, true);
		case "UNSEEN":
			return flag(IMAP.SEEN, false);
		case "KEYWORD":
			return flag(req.nextAtom(), true);
		case "UNKEYWORD":
			return flag(req.nextAtom(), false);
		case "RECENT": // IMAP4rev1: this server never sets \Recent
		case "NEW":
			return c -> false;
		case "OLD":
			return c -> true;
		case "NOT": {
			Key k = parseKey(req);
			return c -> !k.matches(c);
		}
		case "OR": {
			Key a = parseKey(req);
			Key b = parseKey(req);
			return c -> a.matches(c) || b.matches(c);
		}
		case "UID": {
			SequenceSet set = SequenceSet.parse(req.nextAtom());
			if (set.isSaved()) {
				return c -> c.saved.contains(c.info.getUid());
			}
			return c -> set.contains(c.info.getUid(), c.maxUid);
		}
		case "LARGER": {
			long n = number(req.nextAtom());
			return c -> c.info.getSize() > n;
		}
		case "SMALLER": {
			long n = number(req.nextAtom());
			return c -> c.info.getSize() < n;
		}
		case "BEFORE": {
			LocalDate d = date(req.nextAstring());
			return c -> internalDate(c).isBefore(d);
		}
		case "ON": {
			LocalDate d = date(req.nextAstring());
			return c -> internalDate(c).isEqual(d);
		}
		case "SINCE": {
			LocalDate d = date(req.nextAstring());
			return c -> !internalDate(c).isBefore(d);
		}
		case "SENTBEFORE": {
			LocalDate d = date(req.nextAstring());
			return c -> {
				LocalDate s = sentDate(c);
				return s != null && s.isBefore(d);
			};
		}
		case "SENTON": {
			LocalDate d = date(req.nextAstring());
			return c -> {
				LocalDate s = sentDate(c);
				return s != null && s.isEqual(d);
			};
		}
		case "SENTSINCE": {
			LocalDate d = date(req.nextAstring());
			return c -> {
				LocalDate s = sentDate(c);
				return s != null && !s.isBefore(d);
			};
		}
		case "FROM":
			return header("From", req.nextAstring());
		case "TO":
			return header("To", req.nextAstring());
		case "CC":
			return header("Cc", req.nextAstring());
		case "BCC":
			return header("Bcc", req.nextAstring());
		case "SUBJECT":
			return header("Subject", req.nextAstring());
		case "HEADER": {
			String name = req.nextAstring();
			return header(name, req.nextAstring());
		}
		case "BODY": {
			Pattern p = pattern(req.nextAstring());
			return c -> bodyMatches(c.message(), p, 0);
		}
		case "TEXT": {
			Pattern p = pattern(req.nextAstring());
			return c -> headersMatch(c.message(), p) || bodyMatches(c.message(), p, 0);
		}
		default:
			throw new ImapParseException("Unknown search key " + ImapRequestProcessor.sanitize(word));
		}
	}

	private static Key flag(String flag, boolean set) {
		return c -> Mailbox.hasFlag(c.flags(), flag) == set;
	}

	private static long number(String s) {
		try {
			long n = Long.parseLong(s);
			if (n < 0) {
				throw new NumberFormatException();
			}
			return n;
		} catch (NumberFormatException e) {
			throw new ImapParseException("Invalid number in search");
		}
	}

	private static LocalDate date(String s) {
		LocalDate d = ImapStrings.parseDate(s);
		if (d == null) {
			throw new ImapParseException("Invalid date in search");
		}
		return d;
	}

	private static LocalDate internalDate(Ctx c) {
		return Instant.ofEpochMilli(c.info.getInternalDate()).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	private static LocalDate sentDate(Ctx c) throws IOException {
		String h = c.message().getHeader("Date");
		if (h == null) {
			return null;
		}
		try {
			return Rfc2822Date.parseDate(h).getOffsetDateTime().toLocalDate();
		} catch (ParseException | RuntimeException e) {
			return null;
		}
	}

	private static Pattern pattern(String text) {
		return Pattern.compile(text, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	private static Key header(String name, String text) {
		Pattern p = pattern(text);
		boolean any = text.isEmpty();
		return c -> {
			for (String v : c.message().getHeaders(name)) {
				if (any || p.matcher(EncodedWord.decode(v)).find() || p.matcher(v).find()) {
					return true;
				}
			}
			return false;
		};
	}

	private static boolean headersMatch(Message m, Pattern p) {
		for (Header h : m.getHeaders()) {
			String v = h.getValue() == null ? "" : h.getValue();
			if (p.matcher(h.getName() + ": " + EncodedWord.decode(v)).find()) {
				return true;
			}
		}
		return false;
	}

	/** Search the decoded text of the text parts (and attached messages). */
	private static boolean bodyMatches(Message m, Pattern p, int depth) throws IOException {
		if (depth > 20) {
			return false;
		}
		if (m.isMultipart() && !m.getParts().isEmpty()) {
			for (Message part : m.getParts()) {
				if (bodyMatches(part, p, depth + 1)) {
					return true;
				}
			}
			return false;
		}
		String type = m.getMimeType();
		if (type.equals("message/rfc822") || type.equals("message/global")) {
			Message att = m.getAttachedMessage();
			return att != null && (headersMatch(att, p) || bodyMatches(att, p, depth + 1));
		}
		if (!type.startsWith("text/")) {
			return false;
		}
		Charset cs = StandardCharsets.UTF_8;
		String name = m.getContentType().getParameter("charset");
		if (name != null && !name.equalsIgnoreCase("us-ascii")) {
			try {
				cs = Charset.forName(name.trim());
			} catch (RuntimeException e) {
				cs = StandardCharsets.ISO_8859_1;
			}
		}
		try (InputStream in = m.openContent();
				BufferedReader r = new BufferedReader(new InputStreamReader(in, cs), 64 * 1024)) {
			return streamMatches(r, p);
		}
	}

	/** Find a literal pattern in a stream of text, keeping enough overlap between chunks. */
	private static boolean streamMatches(java.io.Reader r, Pattern p) throws IOException {
		int overlap = Math.max(0, p.pattern().length() * 2);
		char[] buf = new char[64 * 1024];
		StringBuilder window = new StringBuilder();
		int n;
		while ((n = r.read(buf)) > 0) {
			window.append(buf, 0, n);
			if (p.matcher(window).find()) {
				return true;
			}
			if (window.length() > overlap) {
				window.delete(0, window.length() - overlap);
			}
		}
		return false;
	}

	/** The UIDs of the messages in a set that match. */
	public static List<Long> run(SearchQuery q, ImapRequestProcessor p, List<Long> uids) throws IOException {
		Mailbox mb = p.getView().getMailbox();
		int max = uids.size();
		long maxUid = uids.isEmpty() ? 0 : uids.get(uids.size() - 1);
		Set<Long> saved = new HashSet<>(p.getSavedSearch());
		List<Long> ret = new ArrayList<>();
		for (int i = 0; i < uids.size(); i++) {
			MessageInfo info = mb.get(uids.get(i));
			if (info == null) {
				continue; // expunged by another session
			}
			Ctx c = new Ctx(info, mb, i + 1, max, maxUid, saved);
			try {
				if (q.matches(c)) {
					ret.add(info.getUid());
				}
			} finally {
				c.close();
			}
		}
		return ret;
	}
}
