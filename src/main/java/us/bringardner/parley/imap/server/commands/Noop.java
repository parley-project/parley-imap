package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** NOOP (RFC 9051 section 6.1.2): also reports mailbox changes. */
public class Noop extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Noop() {
		super("NOOP", ANY);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		if (p.getView() != null) {
			p.getView().getMailbox().refresh(true);
		}
		p.ok(r, "NOOP completed");
	}
}
