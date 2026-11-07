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
		ImapRequestProcessor.MailboxRef ref = p.ref(name);
		if (!ref.isMailbox()) {
			// the same answer as for a user's mailbox that can't be seen: it doesn't tell who exists
			p.no(r, ref.store == null && ref.local != null ? CODE_NONEXISTENT : CODE_CANNOT,
					ref.local == null ? "That is the Other Users namespace" : "No such mailbox");
			return;
		}
		// k on the nearest existing parent (RFC 4314 section 4)
		String parent = p.parentRights(ref);
		if (parent.indexOf('k') < 0) {
			p.noAccess(r, parent);
			return;
		}
		ref.store.create(ref.local, use, p.getPrincipal().getName());
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

	/** Changes mail, so it needs the write permission. */
	@Override
	public us.bringardner.parley.iam.ParleyPermission getPermission() {
		return WRITE_PERMISSION;
	}
}
