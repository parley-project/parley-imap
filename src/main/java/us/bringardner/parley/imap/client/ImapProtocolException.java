package us.bringardner.parley.imap.client;

/** The server sent something this client can't parse. */
public class ImapProtocolException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ImapProtocolException(String message) {
		super(message);
	}
}
