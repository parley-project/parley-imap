package us.bringardner.parley.imap.server.commands;

import java.io.IOException;

import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.imap.server.ImapStrings;
import us.bringardner.parley.mail.store.MailboxAcl;
import us.bringardner.parley.net.sasl.SaslPrep;
import us.bringardner.parley.net.server.AccessControlUnavailableException;
import us.bringardner.parley.net.server.IAccessControlList;

/** What the RFC 4314 commands share. */
final class AclSupport {

	private AclSupport() {
	}

	/**
	 * An identifier from the client, prepared with SASLprep (RFC 4314 section 3).
	 *
	 * @throws ImapParseException (BAD) if it can't be prepared or is empty
	 */
	static String identifier(String raw) {
		String id = SaslPrep.prepare(raw, false);
		if (id == null || id.isEmpty() || id.equals("-")) {
			throw new ImapParseException("Invalid identifier");
		}
		try {
			MailboxAcl.checkIdentifier(id);
		} catch (IllegalArgumentException e) {
			throw new ImapParseException(e.getMessage());
		}
		return id;
	}

	/**
	 * Check an identifier SETACL may give rights to: anyone, a group named with its domain
	 * ({@code $sales@bringardner.us}) or a user the access control list knows; "-" before any
	 * of them for a negative entry.
	 *
	 * @return the identifier to store (a user as the access control list names it, so a
	 * grant made with another spelling still matches the login), or null if it may not be given rights
	 */
	static String grantee(ImapRequestProcessor p, String id) throws IOException {
		boolean negative = id.charAt(0) == MailboxAcl.NEGATIVE;
		String who = negative ? id.substring(1) : id;
		if (who.equals(MailboxAcl.ANYONE)) {
			return id;
		}
		if (who.charAt(0) == MailboxAcl.GROUP) {
			String g = who.substring(1);
			int at = g.indexOf('@');
			return at > 0 && at < g.length() - 1 ? id : null;
		}
		IAccessControlList acl = p.getServer().getAccessControl();
		try {
			us.bringardner.parley.net.server.IPrincipal u = acl == null ? null : acl.findPrincipal(who);
			return u == null ? null : (negative ? "-" : "") + u.getName();
		} catch (AccessControlUnavailableException e) {
			throw new IOException("The users can't be read now: " + e.getMessage(), e);
		}
	}

	/** Check the a right; replies NO and returns null without it. */
	static ImapRequestProcessor.MailboxRef administered(ImapRequestProcessor p, ImapRequest r, String name) throws IOException {
		ImapRequestProcessor.MailboxRef ref = p.ref(name);
		String rights = p.rights(ref);
		if (rights.indexOf('a') < 0) {
			p.noAccess(r, rights);
			return null;
		}
		return ref;
	}

	static String astring(ImapRequestProcessor p, String s) {
		return ImapStrings.astring(s, p.isUtf8());
	}
}
