package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MessageInfo;

/** EXPUNGE (RFC 9051 section 6.4.3) and UID EXPUNGE set (UIDPLUS). */
public class Expunge extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Expunge() {
		super("EXPUNGE", SELECTED);
	}

	@Override
	public boolean isUidCommand() {
		return true;
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		List<Long> only = null;
		if (r.isUid()) {
			only = p.resolve(r.nextAtom(), true);
		}
		r.end();
		if (!checkReadWrite(p, r)) {
			return;
		}
		Mailbox mb = p.getView().getMailbox();
		List<Long> deleted = new ArrayList<>();
		for (MessageInfo m : mb.getMessages()) {
			if (mb.hasFlag(m, DELETED) && (only == null || only.contains(m.getUid()))) {
				deleted.add(m.getUid());
			}
		}
		if (!deleted.isEmpty()) {
			// every view, this one included, is told; ok() sends the EXPUNGE responses
			mb.expunge(deleted, null);
		}
		p.ok(r, "EXPUNGE completed");
	}
}
