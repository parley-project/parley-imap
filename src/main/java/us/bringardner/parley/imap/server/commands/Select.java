package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MailboxView;
import us.bringardner.parley.mail.store.MessageInfo;

/** SELECT (RFC 9051 section 6.3.2); EXAMINE is the read-only form. */
public class Select extends BaseCommand {

	private static final long serialVersionUID = 1L;

	static final String[] SYSTEM_FLAGS = {ANSWERED, FLAGGED, DELETED, SEEN, DRAFT};

	public Select() {
		super("SELECT");
	}

	protected Select(String name) {
		super(name);
	}

	protected boolean isReadOnly() {
		return false;
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		if (r.hasNext()) {
			// select-params (e.g. CONDSTORE, QRESYNC) are not supported
			p.bad(r, "SELECT parameters are not supported");
			return;
		}
		MailboxView view = p.select(name, isReadOnly());
		Mailbox mb = view.getMailbox();
		List<Long> uids = view.uids();
		Set<String> keywords = new LinkedHashSet<>();
		int firstUnseen = 0;
		for (int i = 0; i < uids.size(); i++) {
			MessageInfo m = mb.get(uids.get(i));
			if (m == null) {
				continue;
			}
			Set<String> flags = m.getFlags(mb);
			for (String f : flags) {
				if (!f.startsWith("\\")) {
					keywords.add(f);
				}
			}
			if (firstUnseen == 0 && !Mailbox.hasFlag(flags, SEEN)) {
				firstUnseen = i + 1;
			}
		}
		List<String> flags = new ArrayList<>(List.of(SYSTEM_FLAGS));
		flags.addAll(keywords);
		p.untagged(uids.size() + " EXISTS");
		if (!p.isRev2()) {
			p.untagged("0 RECENT");
		}
		p.untagged("FLAGS " + ImapRequestProcessor.flagList(flags));
		if (view.isReadOnly()) {
			p.untagged(OK + " [PERMANENTFLAGS ()] No permanent flags permitted");
		} else {
			List<String> perm = new ArrayList<>(flags);
			perm.add("\\*");
			p.untagged(OK + " [PERMANENTFLAGS " + ImapRequestProcessor.flagList(perm) + "] Flags permitted");
		}
		if (!p.isRev2() && firstUnseen > 0) {
			p.untagged(OK + " [UNSEEN " + firstUnseen + "] First unseen message");
		}
		p.untagged(OK + " [UIDVALIDITY " + mb.getUidValidity() + "] UIDs valid");
		p.untagged(OK + " [UIDNEXT " + mb.getUidNext() + "] Predicted next UID");
		if (p.isRev2()) {
			p.untagged("LIST () \"/\" " + p.mailboxName(name));
		}
		p.ok(r, view.isReadOnly() ? "READ-ONLY" : "READ-WRITE", getName() + " completed");
	}
}
