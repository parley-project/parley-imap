package us.bringardner.parley.imap.server;

import us.bringardner.parley.core.util.Hex;
import us.bringardner.parley.io.IoUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.Address;
import us.bringardner.parley.mail.EncodedWord;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.mail.MimeHeaderValue;
import us.bringardner.parley.mail.QuotedPrintable;

/**
 * The MIME structure of a message for IMAP: ENVELOPE, BODY and BODYSTRUCTURE
 * (RFC 9051 section 7.5.2), and the part numbering used by BODY[section] and
 * BINARY[section]. Results are cached, since message files never change.
 */
public final class MessageStructure {

	private static final int CACHE_SIZE = 2000;
	private static final Map<String, String> CACHE = Collections
			.synchronizedMap(new LinkedHashMap<String, String>(256, 0.75f, true) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
					return size() > CACHE_SIZE;
				}
			});

	private MessageStructure() {
	}

	/** A node of the part tree: a MIME part, or a message whose body is the part ({@code bodyOnly}). */
	public static final class Node {
		public final Message message;
		/** The node stands for the body of {@code message} (part "1" of a single-part message). */
		public final boolean bodyOnly;
		/** {@code message} is a whole message (the top level or an attached one), not a MIME part. */
		public final boolean isMessage;

		Node(Message message, boolean bodyOnly, boolean isMessage) {
			this.message = message;
			this.bodyOnly = bodyOnly;
			this.isMessage = isMessage;
		}
	}

	static String key(FileSource file, String kind, boolean utf8, boolean rev2) throws IOException {
		return file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified() + "|" + kind + "|" + utf8 + "|" + rev2;
	}

	public static String cached(String key) {
		return CACHE.get(key);
	}

	public static void cache(String key, String value) {
		CACHE.put(key, value);
	}

	// ------------------------------------------------------------------ part tree

	static boolean isMessageType(Message m, boolean rev2) {
		String t = m.getMimeType();
		return t.equals("message/rfc822") || (rev2 && t.equals("message/global"));
	}

	private static List<Node> children(Node n) {
		List<Node> ret = new ArrayList<>();
		if (n.bodyOnly) {
			return ret;
		}
		Message m = n.message;
		if (m.isMultipart() && !m.getParts().isEmpty()) {
			for (Message p : m.getParts()) {
				ret.add(new Node(p, false, false));
			}
		} else if (n.isMessage) {
			ret.add(new Node(m, true, true));
		} else {
			String t = m.getMimeType();
			if (t.equals("message/rfc822") || t.equals("message/global")) {
				Message att = m.getAttachedMessage();
				if (att != null) {
					return children(new Node(att, false, true));
				}
			}
		}
		return ret;
	}

	/** The node of a part number such as {1, 2}, or null if there is none. */
	public static Node resolve(Message root, int[] path) {
		Node cur = new Node(root, false, true);
		for (int n : path) {
			List<Node> kids = children(cur);
			if (n < 1 || n > kids.size()) {
				return null;
			}
			cur = kids.get(n - 1);
		}
		return cur;
	}

	/** The message that HEADER and TEXT refer to at a node, or null. */
	public static Message encapsulated(Node n) {
		if (n.bodyOnly) {
			return null;
		}
		if (n.isMessage) {
			return n.message;
		}
		String t = n.message.getMimeType();
		if (t.equals("message/rfc822") || t.equals("message/global")) {
			return n.message.getAttachedMessage();
		}
		return null;
	}

	/** The (still transfer-encoded) body of a node's message: offset and length in its source. */
	public static long[] bodyRange(Message m) throws IOException {
		long total = m.getSourceLength();
		long header = m.getHeaderLength();
		return new long[] {header, Math.max(0, total - header)};
	}

	/**
	 * The decoded body of a part for BINARY, or null if its transfer encoding is
	 * unknown.
	 */
	public static InputStream openDecoded(Message m) throws IOException {
		long[] r = bodyRange(m);
		String cte = m.getTransferEncoding();
		InputStream raw = IoUtils.buffered(m.openSource(r[0], r[1]));
		switch (cte) {
		case "7bit":
		case "8bit":
		case "binary":
			return raw;
		case "base64":
			return Base64.getMimeDecoder().wrap(raw);
		case "quoted-printable":
			return QuotedPrintable.decoder(raw);
		default:
			raw.close();
			return null;
		}
	}

	// ------------------------------------------------------------------ ENVELOPE

	public static String envelope(Message m, boolean utf8) {
		StringBuilder sb = new StringBuilder("(");
		sb.append(ImapStrings.nstring(m.getHeader("Date"), utf8)).append(' ');
		sb.append(ImapStrings.nstring(m.getHeader("Subject"), utf8)).append(' ');
		String from = m.getHeader("From");
		sb.append(addresses(from, utf8)).append(' ');
		String sender = m.getHeader("Sender");
		sb.append(addresses(sender == null ? from : sender, utf8)).append(' ');
		String replyTo = m.getHeader("Reply-To");
		sb.append(addresses(replyTo == null ? from : replyTo, utf8)).append(' ');
		sb.append(addresses(m.getHeader("To"), utf8)).append(' ');
		sb.append(addresses(m.getHeader("Cc"), utf8)).append(' ');
		sb.append(addresses(m.getHeader("Bcc"), utf8)).append(' ');
		sb.append(ImapStrings.nstring(m.getHeader("In-Reply-To"), utf8)).append(' ');
		sb.append(ImapStrings.nstring(m.getHeader("Message-ID"), utf8));
		return sb.append(')').toString();
	}

	private static String addresses(String header, boolean utf8) {
		if (header == null) {
			return "NIL";
		}
		List<Address> list = Address.parseAddressList(header);
		if (list.isEmpty()) {
			return "NIL";
		}
		StringBuilder sb = new StringBuilder("(");
		for (Address a : list) {
			String name = a.getDisplayName();
			if (name != null && name.trim().isEmpty()) {
				name = null;
			}
			if (name != null && !utf8 && EncodedWord.needsEncoding(name)) {
				name = EncodedWord.encode(name);
			}
			sb.append('(').append(ImapStrings.nstring(name, utf8)).append(" NIL ")
					.append(ImapStrings.nstring(a.getUser(), utf8)).append(' ')
					.append(ImapStrings.nstring(a.getDomain(), utf8)).append(')');
		}
		return sb.append(')').toString();
	}

	// ------------------------------------------------------------------ BODYSTRUCTURE

	/**
	 * BODYSTRUCTURE ({@code extensible}) or BODY of a message or part.
	 *
	 * @param rev2 message/global is described like message/rfc822
	 */
	public static String bodyStructure(Message m, boolean extensible, boolean utf8, boolean rev2) throws IOException {
		StringBuilder sb = new StringBuilder();
		part(sb, m, extensible, utf8, rev2, 0);
		return sb.toString();
	}

	private static void part(StringBuilder sb, Message m, boolean ext, boolean utf8, boolean rev2, int depth)
			throws IOException {
		MimeHeaderValue ct = m.getContentType();
		String type = ct.getValue();
		int slash = type.indexOf('/');
		String major = (slash < 0 ? type : type.substring(0, slash)).toUpperCase(Locale.ROOT);
		String minor = (slash < 0 ? "" : type.substring(slash + 1)).toUpperCase(Locale.ROOT);
		if (major.isEmpty()) {
			major = "TEXT";
			minor = "PLAIN";
		}
		if (m.isMultipart() && !m.getParts().isEmpty() && depth < 50) {
			sb.append('(');
			for (Message p : m.getParts()) {
				part(sb, p, ext, utf8, rev2, depth + 1);
			}
			sb.append(' ').append(ImapStrings.string(minor, utf8));
			if (ext) {
				sb.append(' ').append(params(ct, utf8));
				sb.append(' ').append(disposition(m, utf8));
				sb.append(' ').append(language(m, utf8));
				sb.append(' ').append(ImapStrings.nstring(m.getHeader("Content-Location"), utf8));
			}
			sb.append(')');
			return;
		}
		sb.append('(').append(ImapStrings.string(major, utf8)).append(' ').append(ImapStrings.string(minor, utf8));
		sb.append(' ').append(params(ct, utf8));
		sb.append(' ').append(ImapStrings.nstring(m.getHeader("Content-ID"), utf8));
		sb.append(' ').append(ImapStrings.nstring(m.getHeader("Content-Description"), utf8));
		sb.append(' ').append(ImapStrings.string(m.getTransferEncoding().toUpperCase(Locale.ROOT), utf8));
		long[] range = bodyRange(m);
		sb.append(' ').append(range[1]);
		Message att = null;
		if (isMessageType(m, rev2) && depth < 50) {
			att = m.getAttachedMessage();
		}
		if (att != null) {
			sb.append(' ').append(envelope(att, utf8));
			sb.append(' ');
			part(sb, att, ext, utf8, rev2, depth + 1);
			sb.append(' ').append(lines(m, range));
		} else if (major.equals("TEXT")) {
			sb.append(' ').append(lines(m, range));
		}
		if (ext) {
			sb.append(' ').append(ImapStrings.nstring(m.getHeader("Content-MD5"), utf8));
			sb.append(' ').append(disposition(m, utf8));
			sb.append(' ').append(language(m, utf8));
			sb.append(' ').append(ImapStrings.nstring(m.getHeader("Content-Location"), utf8));
		}
		sb.append(')');
	}

	private static String params(MimeHeaderValue v, boolean utf8) {
		List<MimeHeaderValue.Parameter> list = v.getParameters();
		if (list.isEmpty()) {
			return "NIL";
		}
		StringBuilder sb = new StringBuilder("(");
		for (MimeHeaderValue.Parameter p : list) {
			if (sb.length() > 1) {
				sb.append(' ');
			}
			String name = p.getName();
			String value = p.getValue() == null ? "" : p.getValue();
			if (!utf8 && !isAscii(value)) {
				// RFC 2231 form, so the value isn't lost
				name = name + "*";
				value = "UTF-8'" + (p.getLanguage() == null ? "" : p.getLanguage()) + "'" + percent(value);
			}
			sb.append(ImapStrings.string(name.toUpperCase(Locale.ROOT), utf8)).append(' ')
					.append(ImapStrings.string(value, utf8));
		}
		return sb.append(')').toString();
	}

	private static String disposition(Message m, boolean utf8) {
		MimeHeaderValue cd = m.getContentDisposition();
		if (cd == null || cd.getValue().isEmpty()) {
			return "NIL";
		}
		return "(" + ImapStrings.string(cd.getValue().toUpperCase(Locale.ROOT), utf8) + " " + params(cd, utf8) + ")";
	}

	private static String language(Message m, boolean utf8) {
		String h = m.getHeader("Content-Language");
		if (h == null || h.trim().isEmpty()) {
			return "NIL";
		}
		String[] tags = h.split("\\s*,\\s*");
		if (tags.length == 1) {
			return ImapStrings.string(tags[0].trim(), utf8);
		}
		List<String> items = new ArrayList<>();
		for (String t : tags) {
			if (!t.trim().isEmpty()) {
				items.add(ImapStrings.string(t.trim(), utf8));
			}
		}
		return ImapStrings.list(items);
	}

	/** The number of lines in a body. */
	private static long lines(Message m, long[] range) throws IOException {
		if (range[1] == 0) {
			return 0;
		}
		long n = 0;
		int last = -1;
		try (InputStream in = m.openSource(range[0], range[1])) {
			byte[] buf = new byte[64 * 1024];
			int r;
			while ((r = in.read(buf)) > 0) {
				for (int i = 0; i < r; i++) {
					if (buf[i] == '\n') {
						n++;
					}
				}
				last = buf[r - 1];
			}
		}
		return last == '\n' || last == -1 ? n : n + 1;
	}

	static boolean isAscii(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) >= 0x80) {
				return false;
			}
		}
		return true;
	}

	private static String percent(String s) {
		StringBuilder sb = new StringBuilder();
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$&+-.^_`|~".indexOf(c) >= 0) {
				sb.append((char) c);
			} else {
				sb.append('%');
				Hex.appendUpper(sb, c);
			}
		}
		return sb.toString();
	}
}
