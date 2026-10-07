package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** CAPABILITY (RFC 9051 section 6.1.1). */
public class Capability extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Capability() {
		super("CAPABILITY", ANY);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		p.untagged("CAPABILITY " + p.capabilities());
		p.ok(r, "CAPABILITY completed");
	}
}
