package us.bringardner.parley.imap.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.imap.server.ImapCommandReader;
import us.bringardner.parley.imap.server.ImapInput;
import us.bringardner.parley.imap.server.ImapOutput;
import us.bringardner.parley.imap.server.ImapParseException;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapStrings;
import us.bringardner.parley.imap.server.ImapToken;
import us.bringardner.parley.imap.server.ModifiedUtf7;
import us.bringardner.parley.imap.server.SequenceSet;
import us.bringardner.parley.mail.store.MailStore;

/** The IMAP server's building blocks: names, sequence sets, strings and the command reader. */
public class TestImapParts {

	@Test
	public void testModifiedUtf7() {
		// RFC 3501 section 5.1.3 example
		assertEquals("~peter/mail/&U,BTFw-/&ZeVnLIqe-", ModifiedUtf7.encode("~peter/mail/台北/日本語"));
		assertEquals("~peter/mail/台北/日本語", ModifiedUtf7.decode("~peter/mail/&U,BTFw-/&ZeVnLIqe-"));
		assertEquals("A&-B", ModifiedUtf7.encode("A&B"));
		assertEquals("A&B", ModifiedUtf7.decode("A&-B"));
		assertEquals("&bad", ModifiedUtf7.decode("&bad"), "invalid input is kept");
	}

	@Test
	public void testSegmentEncoding() {
		assertEquals("_sent", MailStore.encodeSegment("Sent"));
		assertEquals("a__b-c%2E1", MailStore.encodeSegment("a_b-c.1"));
		assertEquals("%C3%9Cber", MailStore.encodeSegment("Über"));
		for (String s : new String[] {"Sent", "sent", "a_b", "x.y z", "Ünïcödé", "_", "INBOX", "%41"}) {
			assertEquals(s, MailStore.decodeSegment(MailStore.encodeSegment(s)));
			assertFalse(MailStore.encodeSegment(s).contains("."), "never a dot: " + s);
		}
		assertTrue(!MailStore.encodeSegment("Sent").equals(MailStore.encodeSegment("sent")), "case-sensitive names");
		assertNull(MailStore.decodeSegment("1234567.eml"), "message files aren't mailboxes");
		assertNull(MailStore.decodeSegment("_A"));
		assertEquals("INBOX", MailStore.normalize("inbox"));
		assertEquals("INBOX", MailStore.normalize("Inbox/"));
		assertEquals("Foo/Bar", MailStore.normalize("Foo/Bar/"));
		assertEquals("é", MailStore.normalize("é"), "NFC");
	}

	@Test
	public void testSequenceSet() {
		SequenceSet s = SequenceSet.parse("1:3,7,10:*");
		assertTrue(s.contains(2, 20));
		assertFalse(s.contains(5, 20));
		assertTrue(s.contains(15, 20));
		assertTrue(SequenceSet.parse("5:*").contains(3, 3), "n:* includes * when n > *");
		assertTrue(SequenceSet.parse("$").isSaved());
		assertThrows(ImapParseException.class, () -> SequenceSet.parse("0"));
		assertThrows(ImapParseException.class, () -> SequenceSet.parse("1,,2"));
		assertThrows(ImapParseException.class, () -> SequenceSet.parse("4294967296"));
		assertEquals("1:3,5,7:8", SequenceSet.format(List.of(8L, 1L, 2L, 3L, 5L, 7L)));
		assertEquals("3:4,1", SequenceSet.formatOrdered(List.of(3L, 4L, 1L)));
	}

	@Test
	public void testStrings() {
		assertEquals("\"a \\\"b\\\" \\\\c\"", ImapStrings.string("a \"b\" \\c", false));
		assertEquals("{2}\r\né", ImapStrings.string("é", false), "8-bit as a literal in IMAP4rev1");
		assertEquals("\"é\"", ImapStrings.string("é", true), "quoted UTF-8 in IMAP4rev2");
		assertEquals("{3}\r\na\r\n", ImapStrings.string("a\r\n", true));
		assertEquals("NIL", ImapStrings.nstring(null, true));
		assertEquals("INBOX", ImapStrings.astring("INBOX", false));
		assertEquals("\"a b\"", ImapStrings.astring("a b", false));
		long t = ImapStrings.parseDateTime(" 2-Oct-2026 10:00:00 +0000");
		assertEquals(java.time.Instant.parse("2026-10-02T10:00:00Z").toEpochMilli(), t);
		assertEquals(java.time.LocalDate.of(1994, 2, 1), ImapStrings.parseDate("1-feb-1994"));
	}

	private static ImapRequest read(String input, ByteArrayOutputStream sent) throws IOException {
		ImapCommandReader r = new ImapCommandReader(new ImapInput(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))),
				new ImapOutput(sent), () -> FileSourceFactory.getDefaultFactory().createTempFile("lit", ".tmp"));
		return r.read();
	}

	@Test
	public void testCommandReader() throws IOException {
		ByteArrayOutputStream sent = new ByteArrayOutputStream();
		ImapRequest r = read("a1 FETCH 1:* (FLAGS BODY.PEEK[HEADER.FIELDS (FROM TO)]<0.100>)\r\n", sent);
		assertEquals("a1", r.getTag());
		assertEquals("FETCH", r.getName());
		assertEquals("1:*", r.nextAtom());
		ImapToken.ParenList list = r.nextList();
		assertEquals(2, list.items().size());
		assertEquals("BODY.PEEK[HEADER.FIELDS (FROM TO)]<0.100>", list.items().get(1).text());
		r.end();
		assertEquals(0, sent.size());

		r = read("a2 LOGIN {4}\r\ntony \"se\\\"cret\"\r\n", sent);
		assertEquals("+ Ready for literal data\r\n", sent.toString(StandardCharsets.US_ASCII));
		assertEquals("tony", r.nextAstring());
		assertEquals("se\"cret", r.nextAstring());

		sent.reset();
		r = read("a3 APPEND INBOX {5+}\r\nhello\r\n", sent);
		assertEquals(0, sent.size(), "LITERAL+ needs no continuation");
		r.nextAstring();
		assertEquals("hello", r.next().text());

		assertThrows(ImapParseException.class, () -> read("a4 FETCH (1\r\n", new ByteArrayOutputStream()));
		assertThrows(ImapParseException.class, () -> read("onlytag\r\n", new ByteArrayOutputStream()));
		ImapParseException e = assertThrows(ImapParseException.class, () -> read("+x NOOP\r\n", new ByteArrayOutputStream()));
		assertEquals("*", e.getTag(), "an invalid tag gets an untagged BAD");
	}
}
