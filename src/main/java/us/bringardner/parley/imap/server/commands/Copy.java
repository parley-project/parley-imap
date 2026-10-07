package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.SequenceSet;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MessageInfo;

/** COPY set mailbox (RFC 9051 section 6.4.7), with COPYUID (UIDPLUS). */
public class Copy extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Copy() {
		super("COPY", SELECTED);
	}

	protected Copy(String name) {
		super(name, SELECTED);
	}

	@Override
	public boolean isUidCommand() {
		return true;
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String set = r.nextAtom();
		String name = p.mailboxArg(r.nextAstring());
		r.end();
		List<Long> uids = p.resolve(set, r.isUid());
		if (!checkWrite(p, r) || !checkSource(p, r)) {
			return;
		}
		Mailbox source = p.getView().getMailbox();
		List<MessageInfo> list = new ArrayList<>();
		for (long uid : uids) {
			MessageInfo m = source.get(uid);
			if (m != null) {
				list.add(m);
			}
		}
		if (list.size() < uids.size()) {
			p.no(r, CODE_EXPUNGEISSUED, "Some messages were expunged");
			return;
		}
		Mailbox target;
		try {
			target = p.getStore().open(name);
		} catch (MailStore.StoreException e) {
			p.no(r, CODE_TRYCREATE, "No such mailbox");
			return;
		}
		try {
			List<MessageInfo> added = transfer(p, source, target, list);
			String code = null;
			if (!added.isEmpty()) {
				List<Long> from = new ArrayList<>();
				List<Long> to = new ArrayList<>();
				for (int i = 0; i < added.size(); i++) {
					from.add(list.get(i).getUid());
					to.add(added.get(i).getUid());
				}
				code = "COPYUID " + target.getUidValidity() + " " + SequenceSet.formatOrdered(from) + " "
						+ SequenceSet.formatOrdered(to);
			}
			done(p, r, code);
		} finally {
			p.getStore().getRegistry().release(target);
		}
	}

	protected boolean checkSource(ImapRequestProcessor p, ImapRequest r) throws IOException {
		return true;
	}

	protected List<MessageInfo> transfer(ImapRequestProcessor p, Mailbox source, Mailbox target, List<MessageInfo> list)
			throws IOException {
		return source.copyTo(target, list);
	}

	protected void done(ImapRequestProcessor p, ImapRequest r, String code) throws IOException {
		p.ok(r, code, getName() + " completed");
	}
}
