package us.bringardner.parley.imap.client;

import java.util.Set;

/**
 * Events from the server, for keeping a user interface up to date. Events are
 * delivered on the client's event executor (see
 * {@link ImapClientConfig#setEventExecutor}), never on the network thread, so a
 * listener may call the client. All methods have empty defaults.
 */
public interface ImapListener {

	/** The number of messages in the selected mailbox changed (new mail, or after an expunge). */
	default void exists(String mailbox, long count) {
	}

	/** A message was removed; {@code uid} is -1 if it wasn't known. Later messages move down by one. */
	default void expunged(String mailbox, long msn, long uid) {
	}

	/** A message's flags changed (by this or another client); {@code uid} is -1 if not known. */
	default void flagsChanged(String mailbox, long msn, long uid, Set<String> flags) {
	}

	/** An [ALERT] the user must see (RFC 9051 section 7.1). */
	default void alert(String text) {
	}

	/** The server is closing the connection (BYE). */
	default void bye(String text) {
	}

	/** The connection was lost or closed; {@code cause} is null for a normal LOGOUT. */
	default void disconnected(Exception cause) {
	}
}
