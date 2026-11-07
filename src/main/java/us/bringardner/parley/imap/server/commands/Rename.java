package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** RENAME (RFC 9051 section 6.3.6). Renaming INBOX moves its messages. */
public class Rename extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Rename() {
		super("RENAME");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String from = p.mailboxArg(r.nextAstring());
		String to = p.mailboxArg(r.nextAstring());
		r.end();
		ImapRequestProcessor.MailboxRef src = p.ref(from);
		String rights = p.rights(src);
		if (rights.indexOf('x') < 0) {
			p.noAccess(r, rights);
			return;
		}
		ImapRequestProcessor.MailboxRef dst = p.ref(to);
		if (!dst.isMailbox() || dst.store != src.store) {
			p.no(r, CODE_CANNOT, "A mailbox can't be moved to another user");
			return;
		}
		if (p.parentRights(dst).indexOf('k') < 0) {
			p.no(r, CODE_NOPERM, "Permission denied");
			return;
		}
		src.store.rename(src.local, dst.local);
		p.ok(r, "RENAME completed");
	}

	/** Changes mail, so it needs the write permission. */
	@Override
	public us.bringardner.parley.iam.ParleyPermission getPermission() {
		return WRITE_PERMISSION;
	}
}
