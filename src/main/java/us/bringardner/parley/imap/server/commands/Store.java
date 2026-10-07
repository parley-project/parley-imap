package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxView;
import us.bringardner.parley.mail.store.MessageInfo;

/** STORE set [+|-]FLAGS[.SILENT] flags (RFC 9051 section 6.4.6). */
public class Store extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Store() {
		super("STORE", SELECTED);
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
		String set = r.nextAtom();
		String item = r.nextAtom().toUpperCase(Locale.ROOT);
		Set<String> flags;
		if (r.peek() != null && r.peek().isList()) {
			flags = Append.flags(r.nextList());
		} else {
			List<ImapToken> rest = new ArrayList<>();
			while (r.hasNext()) {
				rest.add(r.next());
			}
			flags = Append.flags(new ImapToken.ParenList(rest));
		}
		r.end();
		Mailbox.FlagOp op;
		boolean silent = item.endsWith(".SILENT");
		String base = silent ? item.substring(0, item.length() - 7) : item;
		switch (base) {
		case "FLAGS":
			op = Mailbox.FlagOp.SET;
			break;
		case "+FLAGS":
			op = Mailbox.FlagOp.ADD;
			break;
		case "-FLAGS":
			op = Mailbox.FlagOp.REMOVE;
			break;
		default:
			throw new ImapParseException("Invalid STORE item " + item);
		}
		List<Long> uids = p.resolve(set, r.isUid());
		if (!checkReadWrite(p, r)) {
			return;
		}
		MailboxView view = p.getView();
		Mailbox mb = view.getMailbox();
		boolean missing = false;
		for (long uid : uids) {
			MessageInfo m = mb.get(uid);
			if (m == null) {
				missing = true;
				continue;
			}
			Set<String> now = mb.storeFlags(m, op, flags, view);
			if (now == null) {
				missing = true;
				continue;
			}
			if (!silent) {
				view.flagsSent(uid);
				p.untagged(view.msn(uid) + " FETCH (" + (r.isUid() ? "UID " + uid + " " : "") + "FLAGS "
						+ ImapRequestProcessor.flagList(now) + ")");
			}
		}
		mb.saveIfDirty();
		if (missing) {
			p.no(r, CODE_EXPUNGEISSUED, "Some messages were expunged");
		} else {
			p.ok(r, "STORE completed");
		}
	}
}
