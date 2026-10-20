package us.bringardner.parley.imap.server;

import us.bringardner.parley.imap.server.ImapRequestProcessor.LoginResult;
import us.bringardner.parley.net.sasl.ISaslAuthenticator;
import us.bringardner.parley.net.sasl.ScramCredentials;
import us.bringardner.parley.net.sasl.ScramMechanism;

/**
 * Checks SASL passwords the way LOGIN does: through {@link ImapRequestProcessor#login}, which
 * prepares both with SASLprep, needs the READ permission, opens the mail store and enters the
 * AUTHENTICATED state.
 * <p>
 * PLAIN is served, and SCRAM-SHA-1 and SCRAM-SHA-256 when the server's access control list keeps
 * SCRAM credentials. SCRAM checks no password here: after it succeeds, the AUTHENTICATE command
 * logs the user in with {@link ImapRequestProcessor#loginVerified(String)}. CRAM-MD5 needs the
 * clear password, which a hash can't give, so it isn't served.
 * <p>
 * One instance per AUTHENTICATE command: it remembers why the login failed, for the reply.
 */
public class ImapSaslAuthenticator implements ISaslAuthenticator {

	private final ImapRequestProcessor processor;
	private LoginResult lastResult;

	public ImapSaslAuthenticator(ImapRequestProcessor processor) {
		this.processor = processor;
	}

	@Override
	public boolean checkPassword(String user, String password) {
		lastResult = processor.login(user, password);
		return lastResult == LoginResult.OK;
	}

	/** @return the result of the last login attempt, or null if the mechanism failed before one */
	public LoginResult getLastResult() {
		return lastResult;
	}

	@Override
	public boolean supports(String mechanism) {
		if ("PLAIN".equals(mechanism)) {
			return true;
		}
		String hash = ScramMechanism.hashOf(mechanism);
		return hash != null && processor.supportsScram(hash);
	}

	/** SCRAM credentials from the server's access control list (the name prepared with SASLprep). */
	@Override
	public ScramCredentials getScramCredentials(String user, String hash) {
		return processor.getScramCredentials(user, hash);
	}
}
