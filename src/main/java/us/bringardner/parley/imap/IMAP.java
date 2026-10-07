package us.bringardner.parley.imap;

import us.bringardner.parley.mail.store.MailboxConstants;

/**
 * IMAP protocol constants: IMAP4rev2 (RFC 9051), with IMAP4rev1 (RFC 3501)
 * compatibility.
 * <p>
 * The mailbox names, special-use attributes and response codes that the mail store
 * also uses are defined once in {@link MailboxConstants} (parley-mail) and repeated here.
 */
public interface IMAP {

	int IMAP_PORT = 143;
	/** IMAP over implicit TLS (RFC 8314). */
	int IMAPS_PORT = 993;

	/** Hierarchy delimiter of this server's mailbox names. */
	char DELIMITER = MailboxConstants.DELIMITER;
	String INBOX = MailboxConstants.INBOX;

	// status responses
	String OK = "OK";
	String NO = "NO";
	String BAD = "BAD";
	String BYE = "BYE";
	String PREAUTH = "PREAUTH";

	// capabilities
	String CAP_IMAP4REV1 = "IMAP4rev1";
	String CAP_IMAP4REV2 = "IMAP4rev2";
	String CAP_UTF8_ACCEPT = "UTF8=ACCEPT";

	// system flags (RFC 9051 section 2.3.2)
	String SEEN = "\\Seen";
	String ANSWERED = "\\Answered";
	String FLAGGED = "\\Flagged";
	String DELETED = "\\Deleted";
	String DRAFT = "\\Draft";
	/** IMAP4rev1 only; never set by this server. */
	String RECENT = "\\Recent";

	// mailbox attributes (RFC 9051 section 7.3.1, RFC 6154)
	String NOSELECT = "\\Noselect";
	String NONEXISTENT = "\\NonExistent";
	String NOINFERIORS = "\\Noinferiors";
	String HAS_CHILDREN = "\\HasChildren";
	String HAS_NO_CHILDREN = "\\HasNoChildren";
	String SUBSCRIBED = "\\Subscribed";
	String SENT = MailboxConstants.SENT;
	String DRAFTS = MailboxConstants.DRAFTS;
	String TRASH = MailboxConstants.TRASH;
	String JUNK = MailboxConstants.JUNK;
	String ARCHIVE = MailboxConstants.ARCHIVE;
	String ALL = "\\All";

	// response codes (RFC 9051 section 7.1)
	String CODE_ALREADYEXISTS = MailboxConstants.CODE_ALREADYEXISTS;
	String CODE_NONEXISTENT = MailboxConstants.CODE_NONEXISTENT;
	String CODE_TRYCREATE = "TRYCREATE";
	String CODE_AUTHENTICATIONFAILED = "AUTHENTICATIONFAILED";
	String CODE_AUTHORIZATIONFAILED = "AUTHORIZATIONFAILED";
	String CODE_PRIVACYREQUIRED = "PRIVACYREQUIRED";
	String CODE_NOPERM = "NOPERM";
	String CODE_CANNOT = MailboxConstants.CODE_CANNOT;
	String CODE_HASCHILDREN = MailboxConstants.CODE_HASCHILDREN;
	String CODE_EXPUNGEISSUED = "EXPUNGEISSUED";
	String CODE_SERVERBUG = "SERVERBUG";
	String CODE_UNAVAILABLE = "UNAVAILABLE";
	String CODE_LIMIT = MailboxConstants.CODE_LIMIT;
	String CODE_TOOBIG = "TOOBIG";
	String CODE_CLOSED = "CLOSED";
	String CODE_UNKNOWN_CTE = "UNKNOWN-CTE";
	String CODE_BADCHARSET = "BADCHARSET";
	String CODE_PARSE = "PARSE";
}
