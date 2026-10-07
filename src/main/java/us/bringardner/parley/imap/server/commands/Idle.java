package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** IDLE (RFC 9051 section 6.3.13, RFC 2177). */
public class Idle extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Idle() {
		super("IDLE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		p.idle(r);
	}
}
