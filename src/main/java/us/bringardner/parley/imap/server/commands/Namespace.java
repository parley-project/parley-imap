package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** NAMESPACE (RFC 9051 section 6.3.10, RFC 2342): the Personal namespace and Other Users ("Other Users/"); no Shared one yet. */
public class Namespace extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Namespace() {
		super("NAMESPACE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		// Personal, and Other Users (RFC 2342); no Shared namespace yet
		p.untagged("NAMESPACE ((\"\" \"" + DELIMITER + "\")) ((" + p.mailboxName(ImapRequestProcessor.OTHER_USERS + DELIMITER)
				+ " \"" + DELIMITER + "\")) NIL");
		p.ok(r, "NAMESPACE completed");
	}

	/** Neither reads nor changes mail, so it needs no permission. */
	@Override
	public boolean requiresAuthorization() {
		return false;
	}
}
