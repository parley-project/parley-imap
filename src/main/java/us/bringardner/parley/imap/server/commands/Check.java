package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/** CHECK (IMAP4rev1 only; removed in IMAP4rev2, where it is treated like NOOP). */
public class Check extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Check() {
		super("CHECK", SELECTED);
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		r.end();
		p.getView().getMailbox().refresh(true);
		p.getView().getMailbox().saveIfDirty();
		p.ok(r, "CHECK completed");
	}
}
