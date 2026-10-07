package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** UNSELECT (RFC 9051 section 6.4.2): leave the selected state without expunging. */
public class Unselect extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Unselect() {
		super("UNSELECT", SELECTED);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		p.deselect();
		p.ok(r, "UNSELECT completed");
	}
}
