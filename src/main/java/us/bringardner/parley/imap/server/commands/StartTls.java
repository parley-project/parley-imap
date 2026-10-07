package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** STARTTLS (RFC 9051 section 6.2.1). */
public class StartTls extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public StartTls() {
		super("STARTTLS", NOT_AUTHENTICATED);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		if (p.isTls()) {
			p.bad(r, "Already using TLS");
			return;
		}
		if (!p.getImapServer().isTlsAvailable()) {
			p.no(r, CODE_UNAVAILABLE, "TLS is not available");
			return;
		}
		p.tagged(r.getTag(), OK, "Begin TLS negotiation now");
		p.startTls();
	}
}
