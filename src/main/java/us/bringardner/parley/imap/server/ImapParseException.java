package us.bringardner.parley.imap.server;

/** A command that can't be parsed; answered with BAD. */
public class ImapParseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String tag;

	public ImapParseException(String message) {
		this(null, message);
	}

	public ImapParseException(String tag, String message) {
		super(message);
		this.tag = tag;
	}

	/** The command's tag, or null if it wasn't read. */
	public String getTag() {
		return tag;
	}
}
