package us.bringardner.parley.imap.server.commands;

/** EXAMINE (RFC 9051 section 6.3.3): SELECT, read-only. */
public class Examine extends Select {

	private static final long serialVersionUID = 1L;

	public Examine() {
		super("EXAMINE");
	}

	@Override
	protected boolean isReadOnly() {
		return true;
	}
}
