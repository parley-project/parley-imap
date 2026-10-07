package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;

/**
 * ENABLE (RFC 9051 section 6.3.1, RFC 5161): IMAP4rev2 switches the session to
 * RFC 9051 behaviour; UTF8=ACCEPT (RFC 6855) allows UTF-8 in IMAP4rev1.
 */
public class Enable extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Enable() {
		super("ENABLE");
	}

	@Override
	public void execute(ImapRequestProcessor p, ImapRequest r) throws IOException {
		List<String> enabled = new ArrayList<>();
		if (!r.hasNext()) {
			p.bad(r, "ENABLE needs at least one capability");
			return;
		}
		while (r.hasNext()) {
			String cap = r.nextAtom();
			if (cap.equalsIgnoreCase(CAP_IMAP4REV2)) {
				if (!p.isRev2()) {
					p.enableRev2();
					enabled.add(CAP_IMAP4REV2);
				}
			} else if (cap.equalsIgnoreCase(CAP_UTF8_ACCEPT)) {
				if (!p.isUtf8Accept()) {
					p.enableUtf8Accept();
					enabled.add(CAP_UTF8_ACCEPT);
				}
			}
		}
		p.untagged("ENABLED" + (enabled.isEmpty() ? "" : " " + String.join(" ", enabled)));
		p.ok(r, "ENABLE completed");
	}
}
