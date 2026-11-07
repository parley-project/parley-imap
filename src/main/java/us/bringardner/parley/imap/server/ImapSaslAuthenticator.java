package us.bringardner.parley.imap.server;

import us.bringardner.parley.mail.server.MailSaslAuthenticator;
import us.bringardner.parley.net.sasl.PlainMechanism;
import us.bringardner.parley.net.sasl.ScramMechanism;

/**
 * parley-mail's {@link MailSaslAuthenticator} for an IMAP session: SASL logins go through the
 * session's {@code authenticate} with the server's user registry, the user name and password
 * prepared with SASLprep, as LOGIN does. The AUTHENTICATE command runs the exchange with
 * {@link #authenticate(us.bringardner.parley.net.sasl.ISaslMechanism, String, us.bringardner.parley.net.sasl.ISaslChannel)}
 * and then opens the mail store.
 * <p>
 * PLAIN is served, and SCRAM-SHA-1 and SCRAM-SHA-256 when the user registry keeps SCRAM
 * credentials for every user. LOGIN isn't an IMAP mechanism here, and CRAM-MD5 needs the clear
 * password, which a hash can't give, so neither is served.
 * <p>
 * One instance per AUTHENTICATE command.
 */
public class ImapSaslAuthenticator extends MailSaslAuthenticator {

	public ImapSaslAuthenticator(ImapRequestProcessor processor) {
		super(processor);
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
