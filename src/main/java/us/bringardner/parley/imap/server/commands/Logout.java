package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** LOGOUT (RFC 9051 section 6.1.3). */
public class Logout extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Logout() {
		super("LOGOUT", ANY);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		p.untagged(BYE + " Logging out");
		p.logout();
		p.tagged(r.getTag(), OK, "LOGOUT completed");
		p.flush();
	}
}
