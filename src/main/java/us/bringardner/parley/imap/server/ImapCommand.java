package us.bringardner.parley.imap.server;

import java.io.IOException;

import us.bringardner.parley.net.server.IStatefulCommand;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.Permission;

/**
 * An IMAP command. As in FtpCommand and Pop3Command, permissions come from the
 * access control list: READ is needed to log in and read mail, WRITE to change
 * anything (flags, mailboxes, messages).
 */
public interface ImapCommand extends IStatefulCommand<ImapCommand.State> {

	IPermission READ_PERMISSION = new Permission("READ");
	IPermission WRITE_PERMISSION = new Permission("WRITE");

	/** Session states, RFC 9051 section 3. */
	enum State {
		NOT_AUTHENTICATED, AUTHENTICATED, SELECTED, LOGOUT
	}

	void execute(ImapRequestProcessor processor, ImapRequest request) throws IOException;

	/** The session states the command may be used in (RFC 9051 section 3). */
	@Override
	java.util.Set<State> getValidStates();

	/** True if the command may be used in this state; by default, if it is in {@link #getValidStates()}. */
	default boolean isValidIn(State state) {
		java.util.Set<State> valid = getValidStates();
		return valid == null || valid.isEmpty() || valid.contains(state);
	}

	/** True for commands that may follow UID (COPY, FETCH, MOVE, SEARCH, STORE, EXPUNGE). */
	default boolean isUidCommand() {
		return false;
	}

	/**
	 * False for FETCH, STORE and SEARCH: while they run, the server must not send
	 * EXPUNGE responses (RFC 9051 section 7.5.1), unless they are UID commands.
	 */
	default boolean allowsExpunge() {
		return true;
	}
}
