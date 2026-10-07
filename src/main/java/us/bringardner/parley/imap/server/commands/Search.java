package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapStrings;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.imap.server.SearchQuery;
import us.bringardner.parley.imap.server.SequenceSet;
import us.bringardner.parley.mail.store.MailboxView;

/**
 * SEARCH [RETURN (options)] [CHARSET name] criteria (RFC 9051 section 6.4.4).
 * IMAP4rev2 sessions, and any SEARCH with RETURN, get an ESEARCH response
 * (RFC 4731); others get the IMAP4rev1 SEARCH response. RETURN (SAVE) keeps the
 * result for "$" (SEARCHRES, RFC 5182).
 */
public class Search extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Search() {
		super("SEARCH", SELECTED);
	}

	@Override
	public boolean isUidCommand() {
		return true;
	}

	@Override
	public boolean allowsExpunge() {
		return false;
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		Set<String> options = null;
		if (r.peek() != null && r.peek().isAtom() && r.peek().text().equalsIgnoreCase("RETURN")) {
			r.next();
			options = new LinkedHashSet<>();
			for (ImapToken t : r.nextList().items()) {
				String o = t.text().toUpperCase(Locale.ROOT);
				if (!o.equals("MIN") && !o.equals("MAX") && !o.equals("ALL") && !o.equals("COUNT") && !o.equals("SAVE")) {
					throw new ImapParseException("Unknown RETURN option " + o);
				}
				options.add(o);
			}
		}
		if (r.peek() != null && r.peek().isAtom() && r.peek().text().equalsIgnoreCase("CHARSET")) {
			r.next();
			String cs = r.nextAstring();
			if (!cs.equalsIgnoreCase("US-ASCII") && !cs.equalsIgnoreCase("UTF-8")) {
				p.no(r, "BADCHARSET (US-ASCII UTF-8)", "Unsupported charset");
				return;
			}
		}
		SearchQuery q = SearchQuery.parse(r);
		MailboxView view = p.getView();
		List<Long> matched = SearchQuery.run(q, p, view.uids());
		boolean uid = r.isUid();
		List<Long> result = new ArrayList<>();
		for (long u : matched) {
			result.add(uid ? u : view.msn(u));
		}
		boolean esearch = options != null || p.isRev2();
		if (options != null && options.contains("SAVE")) {
			boolean minOrMax = options.contains("MIN") || options.contains("MAX");
			boolean allOrCount = options.contains("ALL") || options.contains("COUNT");
			List<Long> saved = new ArrayList<>();
			if (minOrMax && !allOrCount && !matched.isEmpty()) {
				if (options.contains("MIN")) {
					saved.add(matched.get(0));
				}
				if (options.contains("MAX") && matched.size() > 1) {
					saved.add(matched.get(matched.size() - 1));
				}
			} else if (!minOrMax || allOrCount) {
				saved.addAll(matched);
			}
			p.setSavedSearch(saved);
			options.remove("SAVE");
			if (options.isEmpty()) {
				esearch = false; // SAVE alone: no ESEARCH response
				p.ok(r, "SEARCH completed");
				return;
			}
		}
		if (!esearch) {
			StringBuilder sb = new StringBuilder("SEARCH");
			for (long n : result) {
				sb.append(' ').append(n);
			}
			p.untagged(sb.toString());
		} else {
			if (options == null || options.isEmpty()) {
				options = Set.of("ALL");
			}
			StringBuilder sb = new StringBuilder("ESEARCH (TAG " + ImapStrings.string(r.getTag(), false) + ")");
			if (uid) {
				sb.append(" UID");
			}
			if (!result.isEmpty()) {
				if (options.contains("MIN")) {
					sb.append(" MIN ").append(result.stream().mapToLong(Long::longValue).min().getAsLong());
				}
				if (options.contains("MAX")) {
					sb.append(" MAX ").append(result.stream().mapToLong(Long::longValue).max().getAsLong());
				}
			}
			if (options.contains("COUNT")) {
				sb.append(" COUNT ").append(result.size());
			}
			if (options.contains("ALL") && !result.isEmpty()) {
				sb.append(" ALL ").append(SequenceSet.format(result));
			}
			p.untagged(sb.toString());
		}
		p.ok(r, "SEARCH completed");
	}
}
