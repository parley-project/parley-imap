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
		if (!checkWrite(p, r)) {
			return;
		}
		store(p).rename(from, to);
		p.ok(r, "RENAME completed");
	}
}
