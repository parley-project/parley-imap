package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.mail.store.Mailbox;

/** STATUS (RFC 9051 section 6.3.11), with SIZE and DELETED. */
public class Status extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Status() {
		super("STATUS");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		List<String> items = items(r.nextList());
		r.end();
		ImapRequestProcessor.MailboxRef ref = p.ref(name);
		String rights = p.rights(ref);
		if (rights.indexOf('r') < 0) {
			p.noAccess(r, rights);
			return;
		}
		p.untagged(statusLine(p, ref, items));
		p.ok(r, "STATUS completed");
	}

	/** The item names of a STATUS list, validated. */
	public static List<String> items(ImapToken.ParenList list) {
		List<String> ret = new ArrayList<>();
		for (ImapToken t : list.items()) {
			String s = t.text().toUpperCase(Locale.ROOT);
			switch (s) {
			case "MESSAGES":
			case "UIDNEXT":
			case "UIDVALIDITY":
			case "UNSEEN":
			case "DELETED":
			case "SIZE":
			case "RECENT":
				ret.add(s);
				break;
			default:
				throw new ImapParseException("Unknown STATUS item " + s);
			}
		}
		if (ret.isEmpty()) {
			throw new ImapParseException("Empty STATUS item list");
		}
		return ret;
	}

	/** "STATUS name (MESSAGES 2 ...)" */
	public static String statusLine(ImapRequestProcessor p, String name, List<String> items) throws IOException {
		return statusLine(p, p.ref(name), items);
	}

	/** "STATUS name (MESSAGES 2 ...)" for a mailbox the caller checked the r right on */
	public static String statusLine(ImapRequestProcessor p, ImapRequestProcessor.MailboxRef ref, List<String> items) throws IOException {
		if (!ref.isMailbox()) {
			throw new us.bringardner.parley.mail.store.MailStore.StoreException(CODE_NONEXISTENT, "No such mailbox");
		}
		String name = ref.name;
		Mailbox mb = ref.store.open(ref.local);
		try {
			mb.refresh(false);
			StringBuilder sb = new StringBuilder();
			for (String item : items) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(item).append(' ');
				switch (item) {
				case "MESSAGES":
					sb.append(mb.count());
					break;
				case "UIDNEXT":
					sb.append(mb.getUidNext());
					break;
				case "UIDVALIDITY":
					sb.append(mb.getUidValidity());
					break;
				case "UNSEEN":
					sb.append(mb.countWithout(SEEN));
					break;
				case "DELETED":
					sb.append(mb.countWith(DELETED));
					break;
				case "SIZE":
					sb.append(mb.totalSize());
					break;
				default: // RECENT
					sb.append(0);
				}
			}
			return "STATUS " + p.mailboxName(name) + " (" + sb + ")";
		} finally {
			ref.store.getRegistry().release(mb);
		}
	}
}
