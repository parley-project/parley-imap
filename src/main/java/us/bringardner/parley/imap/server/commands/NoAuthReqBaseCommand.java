package us.bringardner.parley.imap.server.commands;

import java.util.Set;

/** Base for commands that don't need a login (CAPABILITY, NOOP, LOGOUT, STARTTLS, ...). */
public abstract class NoAuthReqBaseCommand extends BaseCommand {

	private static final long serialVersionUID = 1L;

	protected NoAuthReqBaseCommand(String command, Set<State> states) {
		super(command, states);
	}

	@Override
	public boolean requiresAuthorization() {
		return false;
	}
}
