package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.mail.store.MailStore;

/**
 * LIST (RFC 9051 section 6.3.9) with LIST-EXTENDED (RFC 5258) selection and
 * return options, LIST-STATUS (RFC 5819), SPECIAL-USE (RFC 6154) and CHILDREN.
 * <pre>
 * LIST [(selection-options)] reference pattern-or-(patterns) [RETURN (options)]
 * </pre>
 */
public class List extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public List() {
		super("LIST");
	}

	protected List(String name) {
		super(name);
	}

	/** What LIST was asked for. */
	static final class Options {
		boolean subscribed;
		boolean recursiveMatch;
		boolean specialUseOnly;
		boolean returnSubscribed;
		boolean returnSpecialUse;
		java.util.List<String> status;
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		Options o = new Options();
		if (r.peek() != null && r.peek().isList()) {
			for (ImapToken t : r.nextList().items()) {
				switch (t.text().toUpperCase(Locale.ROOT)) {
				case "SUBSCRIBED":
					o.subscribed = true;
					break;
				case "REMOTE":
					break;
				case "RECURSIVEMATCH":
					o.recursiveMatch = true;
					break;
				case "SPECIAL-USE":
					o.specialUseOnly = true;
					break;
				default:
					throw new ImapParseException("Unknown LIST selection option " + t.text());
				}
			}
			if (o.recursiveMatch && !o.subscribed) {
				throw new ImapParseException("RECURSIVEMATCH needs another selection option");
			}
		}
		String reference = rawPattern(p, r.nextAstring());
		java.util.List<String> patterns = new ArrayList<>();
		ImapToken pt = r.next();
		if (pt.isList()) {
			for (ImapToken t : ((ImapToken.ParenList) pt).items()) {
				patterns.add(rawPattern(p, t.text()));
			}
		} else {
			patterns.add(rawPattern(p, pt.text()));
		}
		if (r.hasNext()) {
			String kw = r.nextAtom();
			if (!kw.equalsIgnoreCase("RETURN")) {
				throw new ImapParseException("Expected RETURN");
			}
			java.util.List<ImapToken> items = r.nextList().items();
			for (int i = 0; i < items.size(); i++) {
				String opt = items.get(i).text().toUpperCase(Locale.ROOT);
				switch (opt) {
				case "SUBSCRIBED":
					o.returnSubscribed = true;
					break;
				case "CHILDREN":
					break; // always returned
				case "SPECIAL-USE":
					o.returnSpecialUse = true;
					break;
				case "STATUS":
					if (i + 1 >= items.size() || !items.get(i + 1).isList()) {
						throw new ImapParseException("STATUS needs a list of items");
					}
					o.status = Status.items((ImapToken.ParenList) items.get(++i));
					break;
				default:
					throw new ImapParseException("Unknown LIST return option " + opt);
				}
			}
		}
		r.end();
		if (patterns.size() == 1 && patterns.get(0).isEmpty()) {
			// the hierarchy delimiter and root name
			p.untagged("LIST (\\Noselect) \"" + DELIMITER + "\" \"\"");
			p.ok(r, "LIST completed");
			return;
		}
		java.util.List<Pattern> regexes = new ArrayList<>();
		for (String pattern : patterns) {
			if (!pattern.isEmpty()) {
				regexes.add(toRegex(reference + pattern));
			}
		}
		list(p, r, regexes, o);
		p.ok(r, getName() + " completed");
	}

	/** A pattern as sent, decoded from modified UTF-7 in non-UTF-8 sessions (wildcards kept). */
	static String rawPattern(ImapRequestProcessor p, String raw) {
		return p.isUtf8() ? raw : us.bringardner.parley.imap.server.ModifiedUtf7.decode(raw);
	}

	/** "*" matches anything, "%" anything but the delimiter. */
	static Pattern toRegex(String pattern) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '*') {
				sb.append(".*");
			} else if (c == '%') {
				sb.append("[^").append(Pattern.quote(String.valueOf(DELIMITER))).append("]*");
			} else {
				sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(sb.toString(), Pattern.DOTALL);
	}

	/** Case-insensitive variants of the client's patterns, so LIST doesn't compile one per call. */
	private static final Map<String, Pattern> CASE_INSENSITIVE = new LinkedHashMap<String, Pattern>(64, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
			return size() > 64;
		}
	};

	private static Pattern caseInsensitive(Pattern p) {
		synchronized (CASE_INSENSITIVE) {
			return CASE_INSENSITIVE.computeIfAbsent(p.pattern(),
					rx -> Pattern.compile(rx, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
		}
	}

	static boolean matches(java.util.List<Pattern> regexes, String name) {
		for (Pattern p : regexes) {
			if (p.matcher(name).matches()) {
				return true;
			}
			if (name.equals(INBOX) && caseInsensitive(p).matcher(name).matches()) {
				return true;
			}
		}
		return false;
	}

	private void list(ImapRequestProcessor p, ImapRequest r, java.util.List<Pattern> regexes, Options o)
			throws IOException {
		MailStore store = p.getStore();
		Set<String> subs = new TreeSet<>(store.readSubscriptions());
		Map<String, MailStore.Entry> all = new LinkedHashMap<>();
		for (MailStore.Entry e : store.listAll()) {
			all.put(e.name, e);
		}
		// the names to consider, in order
		java.util.List<String> names = new ArrayList<>(all.keySet());
		if (o.subscribed) {
			for (String s : subs) {
				if (!all.containsKey(s)) {
					names.add(s);
				}
			}
		}
		for (String name : names) {
			boolean match = matches(regexes, name);
			MailStore.Entry e = all.get(name);
			boolean subscribed = subs.contains(name);
			String childInfo = null;
			if (o.subscribed && !subscribed) {
				if (!(o.recursiveMatch && match && hasSubscribedChild(subs, name))) {
					continue;
				}
				childInfo = "(\"CHILDINFO\" (\"SUBSCRIBED\"))";
			}
			if (!match) {
				continue;
			}
			if (o.specialUseOnly && (e == null || e.specialUse == null)) {
				continue;
			}
			java.util.List<String> attrs = new ArrayList<>();
			if (e == null) {
				attrs.add(NONEXISTENT);
			} else {
				if (e.noselect) {
					attrs.add(NOSELECT);
				}
				if (e.noinferiors) {
					attrs.add(NOINFERIORS);
				} else {
					attrs.add(e.hasChildren ? HAS_CHILDREN : HAS_NO_CHILDREN);
				}
				if (e.specialUse != null) {
					attrs.add(e.specialUse);
				}
			}
			if (subscribed && childInfo == null && (o.subscribed || o.returnSubscribed)) {
				attrs.add(SUBSCRIBED);
			}
			p.untagged("LIST " + ImapRequestProcessor.flagList(attrs) + " \"" + DELIMITER + "\" " + p.mailboxName(name)
					+ (childInfo == null ? "" : " " + childInfo));
			if (o.status != null && e != null && !e.noselect) {
				try {
					p.untagged(Status.statusLine(p, name, o.status));
				} catch (IOException ex) {
					// the mailbox went away: no STATUS for it
				}
			}
		}
	}

	private static boolean hasSubscribedChild(Set<String> subs, String name) {
		String prefix = name + DELIMITER;
		for (String s : subs) {
			if (s.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
