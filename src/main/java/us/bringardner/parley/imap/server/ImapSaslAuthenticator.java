package us.bringardner.parley.imap.server;

import us.bringardner.parley.imap.server.ImapRequestProcessor.LoginResult;
import us.bringardner.parley.net.sasl.ISaslAuthenticator;

/**
 * Checks SASL passwords the way LOGIN does: through {@link ImapRequestProcessor#login}, which
 * prepares both with SASLprep, needs the READ permission, opens the mail store and enters the
 * AUTHENTICATED state.
 * <p>
 * Only PLAIN is served. The access control list holds password hashes, which can't answer
 * CRAM-MD5 or SCRAM; a store that has the clear password or SCRAM credentials can offer them by
 * overriding {@link #supports(String)} and the matching lookup.
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
		return "PLAIN".equals(mechanism);
	}
}
