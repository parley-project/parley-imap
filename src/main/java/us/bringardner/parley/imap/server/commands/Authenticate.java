package us.bringardner.parley.imap.server.commands;

import us.bringardner.parley.mail.Sasl;
import java.io.IOException;
import java.util.Locale;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/**
 * AUTHENTICATE PLAIN [initial-response] (RFC 9051 section 6.2.2, RFC 4616,
 * SASL-IR RFC 4959). Without an initial response the server sends "+ " and reads
 * the response; "*" cancels.
 */
public class Authenticate extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Authenticate() {
		super("AUTHENTICATE", NOT_AUTHENTICATED);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String mechanism = r.nextAtom().toUpperCase(Locale.ROOT);
		String response = r.hasNext() ? r.nextAtom() : null;
		r.end();
		if (!mechanism.equals("PLAIN")) {
			p.no(r, CODE_CANNOT, "Unsupported authentication mechanism");
			return;
		}
		if (p.isLoginBlockedUntilTls()) {
			p.no(r, CODE_PRIVACYREQUIRED, "Use STARTTLS before logging in");
			return;
		}
		if (response == null) {
			p.continuation("");
			response = p.readLine();
			if (response == null) {
				return;
			}
			response = response.trim();
		}
		if (response.equals("*")) {
			p.bad(r, "Authentication cancelled");
			return;
		}
		byte[] decoded;
		try {
			decoded = Sasl.decodeResponse(response);
		} catch (IllegalArgumentException e) {
			p.bad(r, "Invalid base64");
			return;
		}
		String[] credentials = Sasl.parsePlain(decoded);
		if (credentials == null) {
			p.replyLoginResult(r, ImapRequestProcessor.LoginResult.FAILED);
			return;
		}
		p.replyLoginResult(r, p.login(credentials[0], credentials[1]));
	}
}
