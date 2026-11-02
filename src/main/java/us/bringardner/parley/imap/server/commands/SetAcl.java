package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.MailboxAcl;

/**
 * SETACL mailbox identifier rights (RFC 4314 section 3.1): rights replace the identifier's,
 * +rights add to them, -rights take them away. Needs the a right.
 */
public class SetAcl extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public SetAcl() {
		super("SETACL");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		String id = AclSupport.identifier(r.nextAstring());
		String change = r.nextAstring();
		r.end();
		String rights = change.startsWith("+") || change.startsWith("-") ? change.substring(1) : change;
		try {
			MailboxAcl.canonical(rights);
		} catch (IllegalArgumentException e) {
			throw new ImapParseException(e.getMessage());
		}
		ImapRequestProcessor.MailboxRef ref = AclSupport.administered(p, r, name);
		if (ref == null) {
			return;
		}
		String grantee = AclSupport.grantee(p, id);
		if (grantee == null) {
			p.no(r, CODE_CANNOT, "Rights can be given to anyone, a group with its domain ($sales@example.com) or a user");
			return;
		}
		ref.store.setRights(ref.local, grantee, change);
		p.ok(r, "SETACL completed");
	}
}
