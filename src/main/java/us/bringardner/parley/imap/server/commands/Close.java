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
		if (!view.isReadOnly()) {
			Mailbox mb = view.getMailbox();
			List<Long> deleted = new ArrayList<>();
			for (MessageInfo m : mb.getMessages()) {
				if (mb.hasFlag(m, DELETED)) {
					deleted.add(m.getUid());
				}
			}
			if (!deleted.isEmpty()) {
				mb.expunge(deleted, view);
			}
		}
		p.deselect();
		p.ok(r, "CLOSE completed");
	}
}
