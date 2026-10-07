package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** SUBSCRIBE (RFC 9051 section 6.3.7). */
public class Subscribe extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Subscribe() {
		super("SUBSCRIBE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		r.end();
		store(p).subscribe(name);
		p.ok(r, "SUBSCRIBE completed");
	}
}
