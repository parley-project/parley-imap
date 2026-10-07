package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
			decoded = response.equals("=") ? new byte[0] : Base64.getDecoder().decode(response);
		} catch (IllegalArgumentException e) {
			p.bad(r, "Invalid base64");
			return;
		}
		// authzid NUL authcid NUL passwd
		String[] parts = new String(decoded, StandardCharsets.UTF_8).split("\u0000", -1);
		if (parts.length != 3 || parts[1].isEmpty() || (!parts[0].isEmpty() && !parts[0].equals(parts[1]))) {
			p.replyLoginResult(r, ImapRequestProcessor.LoginResult.FAILED);
			return;
		}
		p.replyLoginResult(r, p.login(parts[1], parts[2]));
	}
}
