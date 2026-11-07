package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxView;
import us.bringardner.parley.mail.store.MessageInfo;

/**
 * CLOSE (RFC 9051 section 6.4.1): remove the \Deleted messages (unless the
 * mailbox is read-only), without EXPUNGE responses, and leave the selected state.
 */
public class Close extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Close() {
		super("CLOSE", SELECTED);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		MailboxView view = p.getView();
		// without e nothing is expunged, and CLOSE still succeeds (RFC 4314 section 4)
		if (!view.isReadOnly() && !view.isRecreated() && p.getSelectedRights().indexOf('e') >= 0) {
			// recreated by another server: its messages aren't the ones marked here, so none go
			Mailbox mb = view.getMailbox();
			long uidValidity = mb.getUidValidity();
			List<Long> deleted = new ArrayList<>();
			for (MessageInfo m : mb.getMessages()) {
				if (mb.hasFlag(m, DELETED)) {
					deleted.add(m.getUid());
				}
			}
			if (!deleted.isEmpty()) {
				mb.expunge(deleted, view, uidValidity);
			}
		}
		p.deselect();
		p.ok(r, "CLOSE completed");
	}

	/** Neither reads nor changes mail, so it needs no permission. */
	@Override
	public boolean requiresAuthorization() {
		return false;
	}
}
