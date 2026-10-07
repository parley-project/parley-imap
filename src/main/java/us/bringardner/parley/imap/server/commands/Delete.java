package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** DELETE (RFC 9051 section 6.3.5). */
public class Delete extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Delete() {
		super("DELETE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		r.end();
		if (!checkWrite(p, r)) {
			return;
		}
		store(p).delete(name);
		p.ok(r, "DELETE completed");
	}
}
