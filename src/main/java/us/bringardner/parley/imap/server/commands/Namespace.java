package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** NAMESPACE (RFC 9051 section 6.3.10): one personal namespace, no shared ones. */
public class Namespace extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Namespace() {
		super("NAMESPACE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		p.untagged("NAMESPACE ((\"\" \"" + DELIMITER + "\")) NIL NIL");
		p.ok(r, "NAMESPACE completed");
	}
}
