package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Pattern;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.MailStore;

/** LSUB (IMAP4rev1, RFC 3501 section 6.3.9; replaced by LIST (SUBSCRIBED) in IMAP4rev2). */
public class Lsub extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Lsub() {
		super("LSUB");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String reference = List.rawPattern(p, r.nextAstring());
		String pattern = List.rawPattern(p, r.nextAstring());
		r.end();
		java.util.List<Pattern> regexes = new ArrayList<>();
		regexes.add(List.toRegex(reference + pattern));
		MailStore store = p.getStore();
		for (String name : store.readSubscriptions()) {
			if (!List.matches(regexes, name)) {
				continue;
			}
			MailStore.Entry e;
			if (ImapRequestProcessor.isOtherUsers(name)) {
				// another user's: only where the user may see it
				ImapRequestProcessor.MailboxRef ref = p.ref(name);
				if (p.rights(ref).indexOf('l') < 0) {
					continue;
				}
				e = ref.store.entry(ref.local);
			} else {
				e = store.entry(name);
			}
			String attrs = e == null || e.noselect ? "(" + NOSELECT + ")" : "()";
			p.untagged("LSUB " + attrs + " \"" + DELIMITER + "\" " + p.mailboxName(name));
		}
		p.ok(r, "LSUB completed");
	}
}
