package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.Map;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.MailboxAcl;

/**
 * GETACL mailbox (RFC 4314 section 3.3): {@code * ACL mailbox identifier rights ...}, the
 * owner's default when the mailbox has no list of its own. The obsolete c and d are added
 * for older clients (section 2.1.1). Needs the a right.
 */
public class GetAcl extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public GetAcl() {
		super("GETACL");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		r.end();
		ImapRequestProcessor.MailboxRef ref = AclSupport.administered(p, r, name);
		if (ref == null) {
			return;
		}
		StringBuilder sb = new StringBuilder("ACL ").append(p.mailboxName(name));
		for (Map.Entry<String, String> e : ref.store.getEffectiveAcl(ref.local).entries().entrySet()) {
			sb.append(' ').append(AclSupport.astring(p, e.getKey()))
					.append(' ').append(AclSupport.astring(p, MailboxAcl.withObsolete(e.getValue())));
		}
		p.untagged(sb.toString());
		p.ok(r, "GETACL completed");
	}

	/** Neither reads nor changes mail, so it needs no permission. */
	@Override
	public boolean requiresAuthorization() {
		return false;
	}
}
