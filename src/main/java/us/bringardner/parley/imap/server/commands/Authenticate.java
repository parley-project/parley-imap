package us.bringardner.parley.imap.server.commands;

import us.bringardner.parley.net.sasl.ISaslChannel;
import us.bringardner.parley.net.sasl.ISaslMechanism;
import us.bringardner.parley.net.sasl.SaslMechanisms;
import us.bringardner.parley.net.sasl.SaslOutcome;
import us.bringardner.parley.net.sasl.SaslServerDriver;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapSaslAuthenticator;

/**
 * AUTHENTICATE mechanism [initial-response] (RFC 9051 section 6.2.2, SASL-IR RFC 4959). The
 * exchange itself is run by {@link SaslServerDriver}; this class supplies the IMAP parts:
 * "+" continuations, "*" to cancel, and the replies. Which mechanisms are offered is decided by
 * {@link ImapSaslAuthenticator} (PLAIN, RFC 4616).
 */
public class Authenticate extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	/** Every mechanism known; the authenticator limits what is offered. */
	private static final SaslMechanisms MECHANISMS = SaslMechanisms.standard("imap");

	public Authenticate() {
		super("AUTHENTICATE", NOT_AUTHENTICATED);
	}

	/**
	 * @return the mechanism names to advertise as AUTH=. TLS is a policy of the server (LOGINDISABLED
	 *         until TLS), so plaintext mechanisms are not filtered here.
	 */
	public static List<String> mechanisms(ImapRequestProcessor p) {
		return MECHANISMS.offered(new ImapSaslAuthenticator(p), true);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = r.nextAtom().toUpperCase(Locale.ROOT);
		String initial = r.hasNext() ? r.nextAtom() : null;
		r.end();
		ImapSaslAuthenticator authenticator = new ImapSaslAuthenticator(p);
		ISaslMechanism mechanism = MECHANISMS.find(name);
		if (mechanism == null || !MECHANISMS.offered(authenticator, true).contains(mechanism.getName())) {
			p.no(r, CODE_CANNOT, "Unsupported authentication mechanism");
			return;
		}
		if (p.isLoginBlockedUntilTls()) {
			p.no(r, CODE_PRIVACYREQUIRED, "Use STARTTLS before logging in");
			return;
		}
		if ("*".equals(initial)) {
			p.bad(r, "Authentication cancelled");
			return;
		}
		SaslOutcome outcome = SaslServerDriver.authenticate(mechanism, authenticator, initial, new ISaslChannel() {
			@Override
			public void sendChallenge(String base64) throws IOException {
				p.continuation(base64);
			}

			@Override
			public String readResponse() throws IOException {
				return p.readLine();
			}
		});
		switch (outcome.getStatus()) {
		case SUCCESS:
			// PLAIN logged in while checking the password; SCRAM proved the user without one
			ImapRequestProcessor.LoginResult ok = authenticator.getLastResult();
			p.replyLoginResult(r, ok != null ? ok : p.loginVerified(outcome.getUser()));
			break;
		case FAILED:
			ImapRequestProcessor.LoginResult result = authenticator.getLastResult();
			if (result == null && authenticator.isUnavailable()) {
				result = ImapRequestProcessor.LoginResult.UNAVAILABLE;
			}
			p.replyLoginResult(r, result != null ? result : ImapRequestProcessor.LoginResult.FAILED);
			break;
		case CANCELLED:
			p.bad(r, "Authentication cancelled");
			break;
		case MALFORMED:
			p.bad(r, "Invalid base64");
			break;
		default:
			// CLOSED: the client went away
			break;
		}
	}
}
