package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import us.bringardner.parley.imap.server.FetchRenderer;
import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxView;
import us.bringardner.parley.mail.store.MessageInfo;

/** FETCH set items (RFC 9051 section 6.4.5). */
public class Fetch extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Fetch() {
		super("FETCH", SELECTED);
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
		ImapToken items = r.next();
		if (r.hasNext()) {
			throw new ImapParseException("FETCH modifiers are not supported");
		}
		FetchRenderer.Request fetch = FetchRenderer.parse(items);
		List<Long> uids = p.resolve(set, r.isUid());
		MailboxView view = p.getView();
		Mailbox mb = view.getMailbox();
		boolean seen = fetch.setsSeen() && !view.isReadOnly();
		boolean missing = false;
		for (long uid : uids) {
			MessageInfo m = mb.get(uid);
			if (m == null) {
				missing = true;
				continue;
			}
			boolean changed = false;
			if (seen && !mb.hasFlag(m, SEEN)) {
				changed = mb.storeFlags(m, Mailbox.FlagOp.ADD, Set.of(SEEN), view) != null;
			}
			FetchRenderer.write(p, view.msn(uid), m, mb, fetch, r.isUid(), changed);
		}
		mb.saveIfDirty();
		if (missing) {
			p.no(r, CODE_EXPUNGEISSUED, "Some messages were expunged");
		} else {
			p.ok(r, "FETCH completed");
		}
	}
}
