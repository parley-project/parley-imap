package us.bringardner.parley.imap.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.imap.server.ImapInput;

/** The client's response parser and the structures built from FETCH responses, without a server. */
public class TestImapClientParts {

	private static ResponseReader reader(String text) {
		return new ResponseReader(new ImapInput(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	public void testStatusResponses() throws IOException {
		ResponseReader r = reader("* OK [UIDVALIDITY 3857529045] UIDs valid\r\n"
				+ "A0001 NO [TRYCREATE] No such mailbox\r\n"
				+ "+ Ready for literal data\r\n"
				+ "* BYE [ALERT] Server shutting down\r\n"
				+ "A0002 OK [COPYUID 38505 304,319:320 3956:3958] Done\r\n");
		Response x = r.read();
		assertTrue(x.isUntagged());
		assertEquals("OK", x.getStatus());
		assertEquals("UIDVALIDITY", x.getCodeName());
		assertEquals("3857529045", x.getCodeArgs());
		assertEquals("UIDs valid", x.getText());
		x = r.read();
		assertEquals("A0001", x.getTag());
		assertFalse(x.isOk());
		assertEquals("TRYCREATE", x.getCodeName());
		assertTrue(r.read().isContinuation());
		x = r.read();
		assertEquals("BYE", x.getStatus());
		assertEquals("ALERT", x.getCodeName());
		x = r.read();
		assertEquals("COPYUID", x.getCodeName());
		assertEquals("38505 304,319:320 3956:3958", x.getCodeArgs());
		assertNull(r.read());
	}

	@Test
	public void testDataWithLiterals() throws IOException {
		ResponseReader r = reader("* LIST (\\HasNoChildren \\Sent) \"/\" {9}\r\nSent Mail\r\n"
				+ "* 12 FETCH (UID 99 FLAGS (\\Seen $Work) BODY[] {5}\r\nab\r\nc)\r\n"
				+ "* 3 EXISTS\r\n"
				+ "* SEARCH 2 84 882\r\n"
				+ "* ESEARCH (TAG \"A1\") UID ALL 3:5,9\r\n");
		Response x = r.read();
		assertEquals("LIST", x.getName());
		assertEquals(Arrays.asList("\\HasNoChildren", "\\Sent"),
				Arrays.asList(x.getTokens().get(1).items().get(0).text(), x.getTokens().get(1).items().get(1).text()));
		assertEquals("Sent Mail", x.getTokens().get(3).text());

		x = r.read();
		assertEquals("FETCH", x.getName());
		assertEquals(12, x.getNumber());
		List<Token> items = x.getTokens().get(2).items();
		assertEquals("UID", items.get(0).text());
		assertEquals(99, items.get(1).number());
		assertArrayEquals("ab\r\nc".getBytes(StandardCharsets.US_ASCII), items.get(5).bytes());

		x = r.read();
		assertEquals("EXISTS", x.getName());
		assertEquals(3, x.getNumber());

		ImapClient.Result res = new ImapClient.Result();
		res.untagged.add(r.read());
		assertEquals(Arrays.asList(2L, 84L, 882L), ImapClient.parseSearch(res));
		res = new ImapClient.Result();
		res.untagged.add(r.read());
		assertEquals(Arrays.asList(3L, 4L, 5L, 9L), ImapClient.parseSearch(res));
	}

	/** A large literal goes to the sink, not into memory. */
	@Test
	public void testLiteralSink() throws IOException {
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			body.append("line ").append(i).append("\r\n");
		}
		String b = body.toString();
		ResponseReader r = reader("* 1 FETCH (UID 7 BODY[] {" + b.length() + "}\r\n" + b + ")\r\n");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		r.setSink(size -> out);
		Response x = r.read();
		Token lit = x.getTokens().get(2).items().get(3);
		assertTrue(lit.isStreamed());
		assertEquals(b.length(), lit.length());
		assertEquals(b, out.toString("US-ASCII"));
	}

	@Test
	public void testEnvelopeAndStructure() throws IOException {
		String fetch = "* 1 FETCH (UID 5 FLAGS (\\Seen) INTERNALDATE \"02-Oct-2026 15:00:00 -0400\" RFC822.SIZE 1234"
				+ " ENVELOPE (\"Fri, 02 Oct 2026 15:00:00 -0400\" \"=?UTF-8?Q?Gr=C3=BC=C3=9Fe?=\""
				+ " ((\"Fred Foo\" NIL \"fred\" \"example.com\")) NIL NIL ((NIL NIL \"tony\" \"bringardner.us\")) NIL NIL NIL \"<m1@x>\")"
				+ " BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"QUOTED-PRINTABLE\" 12 1 NIL NIL NIL NIL)"
				+ "(\"APPLICATION\" \"PDF\" (\"NAME\" \"a.pdf\") NIL NIL \"BASE64\" 400 NIL (\"attachment\" (\"FILENAME*\" \"utf-8''r%C3%A9sum%C3%A9.pdf\")) NIL NIL)"
				+ " \"MIXED\" (\"BOUNDARY\" \"b1\") NIL NIL NIL))\r\n";
		MessageSummary m = ImapClient.parseFetch(reader(fetch).read());
		assertEquals(5, m.getUid());
		assertTrue(m.isSeen());
		assertEquals(1234, m.getSize());
		assertEquals(1790967600000L, m.getInternalDate().toEpochMilli());
		Envelope e = m.getEnvelope();
		assertEquals("Grüße", e.getSubject());
		assertEquals("Fred Foo", e.getFrom().get(0).getDisplayName());
		assertEquals("tony@bringardner.us", e.getTo().get(0).toString());
		assertEquals("<m1@x>", e.getMessageId());
		assertEquals(m.getInternalDate(), e.getDate());

		BodyPart root = m.getStructure();
		assertTrue(root.isMultipart());
		assertEquals("", root.getPartNumber());
		BodyPart text = root.findText("PLAIN");
		assertEquals("1", text.getPartNumber());
		assertEquals("utf-8", text.getCharset());
		BodyPart pdf = root.getChildren().get(1);
		assertEquals("2", pdf.getPartNumber());
		assertEquals("résumé.pdf", pdf.getFilename());
		assertTrue(pdf.isAttachment());
		assertTrue(m.hasAttachments());
	}

	@Test
	public void testUidSets() {
		assertEquals("1:3,7,9:10", ImapClient.uidSet(Arrays.asList(10L, 9L, 1L, 2L, 3L, 7L, 2L)));
		assertEquals(Arrays.asList(1L, 2L, 3L, 7L), ImapClient.expand("1:3,7"));
		assertEquals(Arrays.asList(4L, 5L), ImapClient.expand("5:4"));
	}

	@Test
	public void testCommandRedaction() {
		Command c = new Command("LOGIN", false).string("tony").secret("p@ss word");
		assertFalse(c.toString().contains("p@ss"));
		assertEquals("\"Gr\\\"x\"", new Command("X", true).string("Gr\"x").parts.get(0));
	}

	@Test
	public void testSearchCriteria() {
		SearchCriteria c = SearchCriteria.and(SearchCriteria.unseen(), SearchCriteria.from("José"));
		assertTrue(c.needsUtf8());
		assertEquals("( UNSEEN FROM \"José\" )", c.toString());
		assertFalse(SearchCriteria.not(SearchCriteria.subject("x")).needsUtf8());
	}

	@Test
	public void testCliHelpers() {
		assertEquals(Arrays.asList("search", "FROM", "fred smith", "x\"y", ""),
				ImapCli.split("search FROM \"fred smith\" \"x\\\"y\" \"\""));
		assertEquals(Arrays.asList("FROM", "\"fred smith\""), ImapCli.quoteArgs(Arrays.asList("FROM", "fred smith")));
		assertEquals("1.5 KB", ImapCli.size(1536));
		assertEquals("a\n\nb", ImapCli.stripHtml("<p>a</p><script>x</script><br>b"));
	}
}
