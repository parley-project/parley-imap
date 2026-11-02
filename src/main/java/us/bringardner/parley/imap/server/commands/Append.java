package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapStrings;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MessageInfo;

/**
 * APPEND mailbox [(flags)] [date-time] message (RFC 9051 section 6.3.12), with
 * APPENDUID (UIDPLUS) and the "UTF8 (literal)" form of RFC 6855. Large messages
 * arrive in a temp file and are streamed into the mailbox.
 */
public class Append extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Append() {
		super("APPEND");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		String name = p.mailboxArg(r.nextAstring());
		Set<String> flags = new LinkedHashSet<>();
		if (r.peek() != null && r.peek().isList()) {
			flags = flags(r.nextList());
		}
		long date = System.currentTimeMillis();
		if (r.peek() instanceof ImapToken.Quoted) {
			date = ImapStrings.parseDateTime(r.next().text());
			if (date < 0) {
				throw new ImapParseException("Invalid date-time");
			}
		}
		ImapToken t = r.next();
		if (t.isAtom() && t.text().equalsIgnoreCase("UTF8")) {
			java.util.List<ImapToken> items = r.nextList().items();
			if (items.size() != 1) {
				throw new ImapParseException("UTF8 needs one literal");
			}
			t = items.get(0);
		}
		if (!(t instanceof ImapToken.Literal)) {
			throw new ImapParseException("The message must be a literal");
		}
		r.end();
		ImapToken.Literal message = (ImapToken.Literal) t;
		ImapRequestProcessor.MailboxRef ref = p.ref(name);
		String rights = p.rights(ref);
		if (rights.indexOf('i') < 0) {
			if (ref.personal && (!ref.isMailbox() || ref.store.entry(ref.local) == null)) {
				p.no(r, CODE_TRYCREATE, "No such mailbox");
			} else {
				p.noAccess(r, rights);
			}
			return;
		}
		if (message.length() == 0) {
			p.no(r, CODE_CANNOT, "Empty message");
			return;
		}
		Mailbox mb;
		try {
			mb = ref.store.open(ref.local);
		} catch (MailStore.StoreException e) {
			p.no(r, ref.personal ? CODE_TRYCREATE : CODE_NONEXISTENT, "No such mailbox");
			return;
		}
		MessageInfo added;
		try (InputStream in = message.open()) {
			// flags the rights don't allow are left off; the APPEND still succeeds (RFC 4314 section 4)
			added = mb.append(in, ImapRequestProcessor.allowedFlags(flags, rights), date);
		} finally {
			ref.store.getRegistry().release(mb);
		}
		p.ok(r, "APPENDUID " + mb.getUidValidity() + " " + added.getUid(), "APPEND completed");
	}

	/** A list of flags: system flags and keywords (not \Recent or \*). */
	static Set<String> flags(ImapToken.ParenList list) {
		Set<String> ret = new LinkedHashSet<>();
		for (ImapToken f : list.items()) {
			String s = f.text();
			if (!f.isAtom() || s.equals("\\*")) {
				throw new ImapParseException("Invalid flag");
			}
			if (s.startsWith("\\")) {
				boolean known = false;
				for (String sys : new String[] {SEEN, ANSWERED, FLAGGED, DELETED, DRAFT, RECENT}) {
					known |= sys.equalsIgnoreCase(s);
				}
				if (!known) {
					throw new ImapParseException("Unknown system flag " + s);
				}
			} else if (!ImapStrings.isKeyword(s)) {
				throw new ImapParseException("Invalid keyword " + s);
			}
			ret.add(s);
		}
		return ret;
	}
}
