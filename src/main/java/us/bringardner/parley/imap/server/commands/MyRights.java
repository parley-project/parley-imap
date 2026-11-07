package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.MailboxAcl;

/**
 * MYRIGHTS mailbox (RFC 4314 section 3.5): the user's rights, after the server's permissions
 * limit them. Needs any of l, r, i, k, x or a.
 */
public class MyRights extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public MyRights() {
		super("MYRIGHTS");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		r.end();
		String rights = p.rights(p.ref(name));
		if (!MailboxAcl.hasAny(rights, "lrikxa")) {
			p.noAccess(r, rights);
			return;
		}
		p.untagged("MYRIGHTS " + p.mailboxName(name) + " " + AclSupport.astring(p, MailboxAcl.withObsolete(rights)));
		p.ok(r, "MYRIGHTS completed");
	}

	/** Neither reads nor changes mail, so it needs no permission. */
	@Override
	public boolean requiresAuthorization() {
		return false;
	}
}
