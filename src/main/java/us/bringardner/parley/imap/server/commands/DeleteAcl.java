package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/**
 * DELETEACL mailbox identifier (RFC 4314 section 3.2): removes the identifier's entry
 * ({@code -identifier} for a negative one). Needs the a right.
 */
public class DeleteAcl extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public DeleteAcl() {
		super("DELETEACL");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		String id = AclSupport.identifier(r.nextAstring());
		r.end();
		ImapRequestProcessor.MailboxRef ref = AclSupport.administered(p, r, name);
		if (ref == null) {
			return;
		}
		ref.store.deleteRights(ref.local, id);
		p.ok(r, "DELETEACL completed");
	}
}
