package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import us.bringardner.parley.imap.server.FetchRenderer;
import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxView;
import us.bringardner.parley.mail.store.MessageInfo;

/**
 * FETCH set items (RFC 9051 section 6.4.5). A message expunged since the session last looked
 * (here, or by another server, POP3 or a delivery agent on a shared store) can't be fetched; the
 * others are. UID FETCH ignores such a message and answers OK (RFC 9051 section 6.4.9: a
 * nonexistent UID is ignored without an error); FETCH by sequence number answers NO
 * [EXPUNGEISSUED] (RFC 2180 section 4.1.2). The EXPUNGE reaches the session at its next
 * command that allows one.
 */
public class Fetch extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Fetch() {
		super("FETCH", SELECTED);
	}

	@Override
	public boolean isUidCommand() {
		return true;
	}

	@Override
	public boolean allowsExpunge() {
		return false;
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String set = r.nextAtom();
		ImapToken items = r.next();
		if (r.hasNext()) {
			throw new ImapParseException("FETCH modifiers are not supported");
		}
		FetchRenderer.Request fetch = FetchRenderer.parse(items);
		List<Long> uids = p.resolve(set, r.isUid());
		MailboxView view = p.getView();
		Mailbox mb = view.getMailbox();
		boolean seen = fetch.setsSeen() && !view.isReadOnly();
		boolean missing = false;
		for (long uid : uids) {
			MessageInfo m = mb.get(uid);
			if (m == null) {
				missing = true;
				continue;
			}
			boolean changed = false;
			if (seen && !mb.hasFlag(m, SEEN)) {
				changed = mb.storeFlags(m, Mailbox.FlagOp.ADD, Set.of(SEEN), view) != null;
			}
			try {
				FetchRenderer.write(p, view.msn(uid), m, mb, fetch, r.isUid(), changed);
			} catch (FetchRenderer.MessageGoneException e) {
				if (p.isDebugEnabled()) {
					p.logDebug("FETCH: " + e.getMessage() + ": " + e.getCause());
				}
				missing = true;
			}
		}
		mb.saveIfDirty();
		if (missing && !r.isUid()) {
			p.no(r, CODE_EXPUNGEISSUED, "Some messages were expunged");
		} else {
			p.ok(r, "FETCH completed");
		}
	}
}
