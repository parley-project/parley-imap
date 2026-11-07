package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.MailboxAcl;

/**
 * LISTRIGHTS mailbox identifier (RFC 4314 section 3.4): the rights always granted to the
 * identifier (the owner's l and a), then each right that may be granted on its own. Needs
 * the a right.
 */
public class ListRights extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public ListRights() {
		super("LISTRIGHTS");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		String raw = r.nextAstring();
		String id = AclSupport.identifier(raw);
		r.end();
		ImapRequestProcessor.MailboxRef ref = AclSupport.administered(p, r, name);
		if (ref == null) {
			return;
		}
		String required = id.equals(ref.store.getOwner()) ? MailboxAcl.OWNER_ALWAYS : "";
		StringBuilder sb = new StringBuilder("LISTRIGHTS ").append(p.mailboxName(name))
				.append(' ').append(AclSupport.astring(p, raw))
				.append(' ').append(AclSupport.astring(p, required));
		for (char c : MailboxAcl.minus(MailboxAcl.ALL, required).toCharArray()) {
			sb.append(' ').append(c);
		}
		// the obsolete c and d, which can be granted (RFC 4314 section 2.1.1)
		sb.append(" c d");
		p.untagged(sb.toString());
		p.ok(r, "LISTRIGHTS completed");
	}

	/** Neither reads nor changes mail, so it needs no permission. */
	@Override
	public boolean requiresAuthorization() {
		return false;
	}
}
