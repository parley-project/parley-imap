package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** LOGIN user password (RFC 9051 section 6.2.3). */
public class Login extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Login() {
		super("LOGIN", NOT_AUTHENTICATED);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String user = r.nextAstring();
		String password = r.nextAstring();
		r.end();
		if (p.isLoginBlockedUntilTls()) {
			p.no(r, CODE_PRIVACYREQUIRED, "LOGIN is disabled until STARTTLS");
			return;
		}
		p.replyLoginResult(r, p.login(user, password));
	}
}
