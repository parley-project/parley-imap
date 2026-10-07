package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** UNSUBSCRIBE (RFC 9051 section 6.3.8). */
public class Unsubscribe extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Unsubscribe() {
		super("UNSUBSCRIBE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		r.end();
		store(p).unsubscribe(name);
		p.ok(r, "UNSUBSCRIBE completed");
	}
}
