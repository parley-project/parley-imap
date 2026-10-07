package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.Locale;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapToken;

/**
 * CREATE name [(USE (\Sent))] (RFC 9051 section 6.3.4; the USE parameter is
 * RFC 6154's CREATE-SPECIAL-USE). Missing superior mailboxes are created too.
 */
public class Create extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Create() {
		super("CREATE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		String use = null;
		if (r.hasNext()) {
			ImapToken.ParenList params = r.nextList();
			java.util.List<ImapToken> items = params.items();
			if (items.size() != 2 || !items.get(0).text().equalsIgnoreCase("USE") || !items.get(1).isList()) {
				p.bad(r, "Unsupported CREATE parameters");
				return;
			}
			java.util.List<ImapToken> uses = ((ImapToken.ParenList) items.get(1)).items();
			if (uses.size() > 1) {
				p.no(r, "USEATTR", "Only one special use per mailbox");
				return;
			}
			if (!uses.isEmpty()) {
				use = canonicalUse(uses.get(0).text());
				if (use == null) {
					p.no(r, "USEATTR", "Unknown special use");
					return;
				}
			}
		}
		r.end();
		if (!checkWrite(p, r)) {
			return;
		}
		store(p).create(name, use);
		p.ok(r, "CREATE completed");
	}

	static String canonicalUse(String attr) {
		for (String s : new String[] {SENT, DRAFTS, TRASH, JUNK, ARCHIVE, ALL, "\\Flagged"}) {
			if (s.toLowerCase(Locale.ROOT).equals(attr.toLowerCase(Locale.ROOT))) {
				return s;
			}
		}
		return null;
	}
}
