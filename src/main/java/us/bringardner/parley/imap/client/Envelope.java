package us.bringardner.parley.imap.client;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import us.bringardner.parley.mail.Address;
import us.bringardner.parley.mail.EncodedWord;
import us.bringardner.parley.mail.Rfc2822Date;

/**
 * A message's ENVELOPE (RFC 9051 section 7.5.2): the main header fields, parsed
 * by the server. Subjects and display names are decoded (RFC 2047).
 */
public final class Envelope {

	String dateText;
	String subject;
	List<Address> from = Collections.emptyList();
	List<Address> sender = Collections.emptyList();
	List<Address> replyTo = Collections.emptyList();
	List<Address> to = Collections.emptyList();
	List<Address> cc = Collections.emptyList();
	List<Address> bcc = Collections.emptyList();
	String inReplyTo;
	String messageId;

	/** The Date header as sent, or null. */
	public String getDateText() {
		return dateText;
	}

	/** The Date header, or null if missing or unparseable. */
	public Instant getDate() {
		if (dateText == null) {
			return null;
		}
		try {
			return Rfc2822Date.parseDate(dateText).getOffsetDateTime().toInstant();
		} catch (Exception e) {
			return null;
		}
	}

	public String getSubject() {
		return subject;
	}

	public List<Address> getFrom() {
		return from;
	}

	public List<Address> getSender() {
		return sender;
	}

	public List<Address> getReplyTo() {
		return replyTo;
	}

	public List<Address> getTo() {
		return to;
	}

	public List<Address> getCc() {
		return cc;
	}

	public List<Address> getBcc() {
		return bcc;
	}

	public String getInReplyTo() {
		return inReplyTo;
	}

	public String getMessageId() {
		return messageId;
	}

	static Envelope parse(Token t) {
		List<Token> i = t.items();
		if (!t.isList() || i.size() < 10) {
			throw new ImapProtocolException("Invalid ENVELOPE " + t);
		}
		Envelope e = new Envelope();
		e.dateText = i.get(0).text();
		e.subject = i.get(1).isNil() ? null : EncodedWord.decode(i.get(1).text());
		e.from = addresses(i.get(2));
		e.sender = addresses(i.get(3));
		e.replyTo = addresses(i.get(4));
		e.to = addresses(i.get(5));
		e.cc = addresses(i.get(6));
		e.bcc = addresses(i.get(7));
		e.inReplyTo = i.get(8).text();
		e.messageId = i.get(9).text();
		return e;
	}

	/** An address list; group start and end markers are skipped. */
	static List<Address> addresses(Token t) {
		if (!t.isList()) {
			return Collections.emptyList();
		}
		List<Address> ret = new ArrayList<>();
		for (Token a : t.items()) {
			List<Token> p = a.items();
			if (p.size() < 4) {
				continue;
			}
			String name = p.get(0).text();
			String mailbox = p.get(2).text();
			String host = p.get(3).text();
			if (host == null) {
				continue; // group syntax marker
			}
			ret.add(new Address(name == null ? null : EncodedWord.decode(name), mailbox, host));
		}
		return Collections.unmodifiableList(ret);
	}

	@Override
	public String toString() {
		return "Envelope[" + from + " \"" + subject + "\" " + dateText + "]";
	}
}
