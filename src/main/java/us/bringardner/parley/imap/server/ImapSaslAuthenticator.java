package us.bringardner.parley.imap.server;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.imap.server.ImapRequestProcessor.LoginResult;
import us.bringardner.parley.mail.server.MailSaslAuthenticator;
import us.bringardner.parley.net.sasl.PlainMechanism;
import us.bringardner.parley.net.sasl.ScramMechanism;

/**
 * parley-mail's {@link MailSaslAuthenticator} for an IMAP session: SASL logins go through the
 * session's {@code authenticate} with the server's user registry, the user name and password
 * prepared with SASLprep, as LOGIN does; when one succeeds the read permission is checked, the
 * mail store is opened and the session enters the AUTHENTICATED state.
 * <p>
 * PLAIN is served, and SCRAM-SHA-1 and SCRAM-SHA-256 when the user registry keeps SCRAM
 * credentials for every user. SCRAM checks no password here: after it succeeds, the AUTHENTICATE
 * command logs the user in with {@link ImapRequestProcessor#loginVerified(ImapSaslAuthenticator, String)}.
 * CRAM-MD5 needs the clear password, which a hash can't give, so it isn't served.
 * <p>
 * One instance per AUTHENTICATE command: it remembers why the login failed, for the reply.
 */
public class ImapSaslAuthenticator extends MailSaslAuthenticator {

	private final ImapRequestProcessor processor;
	private LoginResult lastResult;

	public ImapSaslAuthenticator(ImapRequestProcessor processor) {
		super(processor);
		this.processor = processor;
	}

	/** PLAIN: log in with the registry, then check read and open the mail store. */
	@Override
	public boolean checkPassword(String user, String password) {
		super.checkPassword(user, password);
		AuthenticationResult result = getResult();
		lastResult = processor.afterAuthentication(result, user);
		return lastResult == LoginResult.OK;
	}

	/** @return the result of the last login attempt, or null if the mechanism failed before one */
	public LoginResult getLastResult() {
		return lastResult;
	}

	/** PLAIN, and SCRAM when the user registry can serve it; LOGIN isn't an IMAP mechanism here. */
	@Override
	public boolean supports(String mechanism) {
		if (PlainMechanism.NAME.equals(mechanism) || ScramMechanism.hashOf(mechanism) != null) {
			return super.supports(mechanism);
		}
		return false;
	}
}
