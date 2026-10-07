package us.bringardner.parley.imap.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.Message;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.mail.store.MailStore;
import us.bringardner.parley.mail.store.Mailbox;
import us.bringardner.parley.mail.store.MessageInfo;

/**
 * FETCH (RFC 9051 section 6.4.5): parses the data items and writes the FETCH
 * responses. Message content is streamed from the message file as literals, so
 * a message of any size can be fetched.
 */
public final class FetchRenderer {

	enum Kind {
		FLAGS, UID, INTERNALDATE, RFC822_SIZE, ENVELOPE, BODY, BODYSTRUCTURE, SECTION, BINARY, BINARY_SIZE, RFC822,
		RFC822_HEADER, RFC822_TEXT
	}

	/** One requested data item. */
	static final class Att {
		Kind kind;
		int[] path = new int[0];
		/** null, HEADER, HEADER.FIELDS, HEADER.FIELDS.NOT, TEXT or MIME. */
		String spec;
		List<String> fields = new ArrayList<>();
		boolean peek;
		long origin = -1;
		long count = -1;

		Att(Kind kind) {
			this.kind = kind;
		}

		boolean setsSeen() {
			return !peek && (kind == Kind.SECTION || kind == Kind.BINARY || kind == Kind.RFC822 || kind == Kind.RFC822_TEXT);
		}

		boolean needsMessage() {
			return kind == Kind.ENVELOPE || kind == Kind.BODY || kind == Kind.BODYSTRUCTURE || kind == Kind.SECTION
					|| kind == Kind.BINARY || kind == Kind.BINARY_SIZE || kind == Kind.RFC822_HEADER || kind == Kind.RFC822_TEXT;
		}

		/** The item name in the response, e.g. "BODY[1.MIME]<0>". */
		String responseName() {
			switch (kind) {
			case SECTION:
				return "BODY[" + sectionText() + "]" + (origin >= 0 ? "<" + origin + ">" : "");
			case BINARY:
				return "BINARY[" + pathText() + "]" + (origin >= 0 ? "<" + origin + ">" : "");
			case BINARY_SIZE:
				return "BINARY.SIZE[" + pathText() + "]";
			case RFC822_SIZE:
				return "RFC822.SIZE";
			case RFC822_HEADER:
				return "RFC822.HEADER";
			case RFC822_TEXT:
				return "RFC822.TEXT";
			default:
				return kind.name();
			}
		}

		private String pathText() {
			StringBuilder sb = new StringBuilder();
			for (int p : path) {
				if (sb.length() > 0) {
					sb.append('.');
				}
				sb.append(p);
			}
			return sb.toString();
		}

		private String sectionText() {
			StringBuilder sb = new StringBuilder(pathText());
			if (spec != null) {
				if (sb.length() > 0) {
					sb.append('.');
				}
				sb.append(spec);
				if (spec.startsWith("HEADER.FIELDS")) {
					sb.append(" (").append(String.join(" ", fields)).append(')');
				}
			}
			return sb.toString();
		}
	}

	private FetchRenderer() {
	}

	/** The parsed data items of a FETCH command. */
	public static final class Request {
		final List<Att> atts;

		Request(List<Att> atts) {
			this.atts = atts;
		}

		/** True if fetching sets \Seen (BODY[...], BINARY[...], RFC822, RFC822.TEXT). */
		public boolean setsSeen() {
			return FetchRenderer.setsSeen(atts);
		}
	}

	public static Request parse(ImapToken token) {
		return new Request(parseAtts(token));
	}

	/** Write one FETCH response (see {@link #write(ImapRequestProcessor, int, MessageInfo, Mailbox, List, boolean, boolean)}). */
	public static void write(ImapRequestProcessor p, int msn, MessageInfo info, Mailbox mb, Request req, boolean addUid,
			boolean addFlags) throws IOException {
		write(p, msn, info, mb, req.atts, addUid, addFlags);
	}

	// ------------------------------------------------------------------ parsing

	/** Parse the data items of FETCH: one item, a macro or a parenthesized list. */
	static List<Att> parseAtts(ImapToken token) {
		List<Att> ret = new ArrayList<>();
		if (token.isList()) {
			List<ImapToken> items = ((ImapToken.ParenList) token).items();
			if (items.isEmpty()) {
				throw new ImapParseException("Empty FETCH item list");
			}
			for (ImapToken t : items) {
				if (!t.isAtom()) {
					throw new ImapParseException("Invalid FETCH item");
				}
				ret.add(parseItem(t.text()));
			}
			return ret;
		}
		if (!token.isAtom()) {
			throw new ImapParseException("Invalid FETCH item");
		}
		String s = token.text().toUpperCase(Locale.ROOT);
		switch (s) {
		case "ALL":
			return List.of(new Att(Kind.FLAGS), new Att(Kind.INTERNALDATE), new Att(Kind.RFC822_SIZE), new Att(Kind.ENVELOPE));
		case "FAST":
			return List.of(new Att(Kind.FLAGS), new Att(Kind.INTERNALDATE), new Att(Kind.RFC822_SIZE));
		case "FULL":
			return List.of(new Att(Kind.FLAGS), new Att(Kind.INTERNALDATE), new Att(Kind.RFC822_SIZE), new Att(Kind.ENVELOPE),
					new Att(Kind.BODY));
		default:
			ret.add(parseItem(token.text()));
			return ret;
		}
	}

	static Att parseItem(String text) {
		int bracket = text.indexOf('[');
		String name = (bracket < 0 ? text : text.substring(0, bracket)).toUpperCase(Locale.ROOT);
		if (bracket < 0) {
			switch (name) {
			case "FLAGS":
				return new Att(Kind.FLAGS);
			case "UID":
				return new Att(Kind.UID);
			case "INTERNALDATE":
				return new Att(Kind.INTERNALDATE);
			case "RFC822.SIZE":
				return new Att(Kind.RFC822_SIZE);
			case "ENVELOPE":
				return new Att(Kind.ENVELOPE);
			case "BODY":
				return new Att(Kind.BODY);
			case "BODYSTRUCTURE":
				return new Att(Kind.BODYSTRUCTURE);
			case "RFC822":
				return new Att(Kind.RFC822);
			case "RFC822.HEADER":
				return new Att(Kind.RFC822_HEADER);
			case "RFC822.TEXT":
				return new Att(Kind.RFC822_TEXT);
			default:
				throw new ImapParseException("Unknown FETCH item " + ImapRequestProcessor.sanitize(text));
			}
		}
		int close = text.lastIndexOf(']');
		if (close < bracket) {
			throw new ImapParseException("Missing ] in FETCH item");
		}
		String section = text.substring(bracket + 1, close);
		String rest = text.substring(close + 1);
		Att a;
		switch (name) {
		case "BODY":
			a = new Att(Kind.SECTION);
			break;
		case "BODY.PEEK":
			a = new Att(Kind.SECTION);
			a.peek = true;
			break;
		case "BINARY":
			a = new Att(Kind.BINARY);
			break;
		case "BINARY.PEEK":
			a = new Att(Kind.BINARY);
			a.peek = true;
			break;
		case "BINARY.SIZE":
			a = new Att(Kind.BINARY_SIZE);
			break;
		default:
			throw new ImapParseException("Unknown FETCH item " + ImapRequestProcessor.sanitize(text));
		}
		parseSection(a, section);
		if (a.kind != Kind.SECTION && a.spec != null) {
			throw new ImapParseException("BINARY takes only a part number");
		}
		if (!rest.isEmpty()) {
			if (a.kind == Kind.BINARY_SIZE || !rest.startsWith("<") || !rest.endsWith(">")) {
				throw new ImapParseException("Invalid partial range");
			}
			String[] p = rest.substring(1, rest.length() - 1).split("\\.", -1);
			try {
				a.origin = Long.parseLong(p[0]);
				a.count = p.length > 1 ? Long.parseLong(p[1]) : -1;
				if (p.length != 2 || a.origin < 0 || a.count <= 0) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				throw new ImapParseException("Invalid partial range");
			}
		}
		return a;
	}

	private static void parseSection(Att a, String s) {
		List<Integer> path = new ArrayList<>();
		int i = 0;
		while (i < s.length() && Character.isDigit(s.charAt(i))) {
			int j = i;
			while (j < s.length() && Character.isDigit(s.charAt(j))) {
				j++;
			}
			int n;
			try {
				n = Integer.parseInt(s.substring(i, j));
			} catch (NumberFormatException e) {
				throw new ImapParseException("Invalid part number");
			}
			if (n < 1) {
				throw new ImapParseException("Invalid part number");
			}
			path.add(n);
			i = j;
			if (i < s.length() && s.charAt(i) == '.') {
				i++;
			} else {
				break;
			}
		}
		a.path = path.stream().mapToInt(Integer::intValue).toArray();
		String spec = s.substring(i).trim();
		if (spec.isEmpty()) {
			if (s.endsWith(".")) {
				throw new ImapParseException("Invalid section");
			}
			return;
		}
		String upper = spec.toUpperCase(Locale.ROOT);
		if (upper.equals("HEADER") || upper.equals("TEXT") || upper.equals("MIME")) {
			if (upper.equals("MIME") && a.path.length == 0) {
				throw new ImapParseException("MIME needs a part number");
			}
			a.spec = upper;
			return;
		}
		String fieldsSpec = upper.startsWith("HEADER.FIELDS.NOT") ? "HEADER.FIELDS.NOT"
				: upper.startsWith("HEADER.FIELDS") ? "HEADER.FIELDS" : null;
		if (fieldsSpec == null) {
			throw new ImapParseException("Invalid section " + ImapRequestProcessor.sanitize(spec));
		}
		String list = spec.substring(fieldsSpec.length()).trim();
		if (!list.startsWith("(") || !list.endsWith(")")) {
			throw new ImapParseException("Expected a list of header fields");
		}
		for (String f : list.substring(1, list.length() - 1).trim().split("\\s+")) {
			if (f.length() >= 2 && f.startsWith("\"") && f.endsWith("\"")) {
				f = f.substring(1, f.length() - 1);
			}
			if (!f.isEmpty()) {
				a.fields.add(f.toUpperCase(Locale.ROOT));
			}
		}
		if (a.fields.isEmpty()) {
			throw new ImapParseException("Empty header field list");
		}
		a.spec = fieldsSpec;
	}

	// ------------------------------------------------------------------ writing

	/** A piece of a FETCH response, prepared before anything is written. */
	private interface Piece {
		void write(ImapOutput out) throws IOException;
	}

	@FunctionalInterface
	private interface Opener {
		InputStream open() throws IOException;
	}

	/**
	 * Write the FETCH response for one message. Everything that can fail (an
	 * unknown transfer encoding, an unreadable file) is checked before the
	 * response starts.
	 *
	 * @param addUid  include UID (UID FETCH)
	 */
	static void write(ImapRequestProcessor p, int msn, MessageInfo info, Mailbox mb, List<Att> atts, boolean addUid,
			boolean addFlags) throws IOException {
		boolean utf8 = p.isUtf8();
		boolean rev2 = p.isRev2();
		FileSource file = !utf8 && info.isUtf8() ? mb.surrogate(info) : info.getFile();
		long size = file == info.getFile() ? info.getSize() : file.length();
		List<Piece> pieces = new ArrayList<>();
		boolean hasUid = false;
		boolean hasFlags = false;
		for (Att a : atts) {
			hasUid |= a.kind == Kind.UID;
			hasFlags |= a.kind == Kind.FLAGS;
		}
		if (addUid && !hasUid) {
			pieces.add(out -> out.write("UID " + info.getUid()));
		}
		Message msg = null;
		try {
			for (Att a : atts) {
				if (a.needsMessage() && msg == null) {
					msg = Message.parse(file);
				}
				pieces.add(piece(p, a, info, mb, file, size, msg, utf8, rev2));
			}
			if (addFlags && !hasFlags) {
				pieces.add(piece(p, new Att(Kind.FLAGS), info, mb, file, size, msg, utf8, rev2));
			}
			ImapOutput out = p.getOutput();
			out.write("* " + msn + " FETCH (");
			boolean first = true;
			for (Piece piece : pieces) {
				if (!first) {
					out.write(" ");
				}
				first = false;
				piece.write(out);
			}
			out.write(")").crlf();
		} finally {
			if (msg != null) {
				msg.close();
			}
		}
	}

	private static Piece piece(ImapRequestProcessor p, Att a, MessageInfo info, Mailbox mb, FileSource file, long size,
			Message msg, boolean utf8, boolean rev2) throws IOException {
		String name = a.responseName();
		switch (a.kind) {
		case FLAGS: {
			String flags = ImapRequestProcessor.flagList(info.getFlags(mb));
			p.getView().flagsSent(info.getUid());
			return out -> out.write("FLAGS " + flags);
		}
		case UID:
			return out -> out.write("UID " + info.getUid());
		case INTERNALDATE:
			return out -> out.write("INTERNALDATE " + ImapStrings.dateTime(info.getInternalDate()));
		case RFC822_SIZE:
			return out -> out.write("RFC822.SIZE " + size);
		case ENVELOPE: {
			String key = MessageStructure.key(file, "E", utf8, rev2);
			String env = MessageStructure.cached(key);
			if (env == null) {
				env = MessageStructure.envelope(msg, utf8);
				MessageStructure.cache(key, env);
			}
			String v = env;
			return out -> out.write("ENVELOPE " + v);
		}
		case BODY:
		case BODYSTRUCTURE: {
			boolean ext = a.kind == Kind.BODYSTRUCTURE;
			String key = MessageStructure.key(file, ext ? "S" : "B", utf8, rev2);
			String bs = MessageStructure.cached(key);
			if (bs == null) {
				bs = MessageStructure.bodyStructure(msg, ext, utf8, rev2);
				MessageStructure.cache(key, bs);
			}
			String v = bs;
			return out -> out.write(name + " " + v);
		}
		case RFC822:
			return literal(name, size, () -> file.getInputStream(), a);
		case RFC822_HEADER: {
			long h = msg.getHeaderLength();
			Message m = msg;
			return literal(name, h, () -> m.openSource(0, h), a);
		}
		case RFC822_TEXT: {
			long[] r = MessageStructure.bodyRange(msg);
			Message m = msg;
			return literal(name, r[1], () -> m.openSource(r[0], r[1]), a);
		}
		case SECTION:
			return section(a, name, msg, file, size);
		case BINARY:
		case BINARY_SIZE:
			return binary(a, name, msg, file, size);
		default:
			throw new IllegalStateException(a.kind.name());
		}
	}

	private static Piece section(Att a, String name, Message root, FileSource file, long size) throws IOException {
		if (a.path.length == 0 && a.spec == null) {
			return literal(name, size, () -> file.getInputStream(), a);
		}
		MessageStructure.Node node = MessageStructure.resolve(root, a.path);
		if (node == null) {
			return empty(name);
		}
		if (a.spec == null) {
			long[] r = MessageStructure.bodyRange(node.message);
			Message m = node.message;
			return literal(name, r[1], () -> m.openSource(r[0], r[1]), a);
		}
		if (a.spec.equals("MIME")) {
			Message m = node.message;
			long h = m.getHeaderLength();
			return literal(name, h, () -> m.openSource(0, h), a);
		}
		Message target = a.path.length == 0 ? root : MessageStructure.encapsulated(node);
		if (target == null) {
			return empty(name);
		}
		if (a.spec.equals("HEADER")) {
			long h = target.getHeaderLength();
			return literal(name, h, () -> target.openSource(0, h), a);
		}
		if (a.spec.equals("TEXT")) {
			long[] r = MessageStructure.bodyRange(target);
			return literal(name, r[1], () -> target.openSource(r[0], r[1]), a);
		}
		byte[] header;
		try (InputStream in = target.openSource(0, target.getHeaderLength())) {
			header = in.readAllBytes();
		}
		byte[] filtered = filterFields(header, a.fields, a.spec.equals("HEADER.FIELDS.NOT"));
		return literal(name, filtered.length, () -> new java.io.ByteArrayInputStream(filtered), a);
	}

	private static Piece binary(Att a, String name, Message root, FileSource file, long size) throws IOException {
		Opener opener;
		if (a.path.length == 0) {
			opener = () -> file.getInputStream();
		} else {
			MessageStructure.Node node = MessageStructure.resolve(root, a.path);
			if (node == null) {
				return a.kind == Kind.BINARY_SIZE ? out -> out.write(name + " 0") : empty(name);
			}
			Message m = node.message;
			try (InputStream test = MessageStructure.openDecoded(m)) {
				if (test == null) {
					throw new MailStore.StoreException(IMAP.CODE_UNKNOWN_CTE,
							"Unknown Content-Transfer-Encoding " + m.getTransferEncoding());
				}
			}
			opener = () -> MessageStructure.openDecoded(m);
		}
		// decoded size, and whether it has NUL (then it needs literal8)
		long n = 0;
		boolean nul = false;
		try (InputStream in = opener.open()) {
			byte[] buf = new byte[64 * 1024];
			int r;
			while ((r = in.read(buf)) > 0) {
				n += r;
				for (int i = 0; i < r && !nul; i++) {
					nul = buf[i] == 0;
				}
			}
		}
		if (a.kind == Kind.BINARY_SIZE) {
			long total = n;
			return out -> out.write(name + " " + total);
		}
		boolean literal8 = nul;
		long total = n;
		Opener o = opener;
		return out -> {
			long[] r = partial(a, total);
			out.write(name + " ");
			try (InputStream in = o.open()) {
				skipFully(in, r[0]);
				out.literal(r[1], in, literal8);
			}
		};
	}

	private static Piece empty(String name) {
		return out -> out.write(name + " \"\"");
	}

	private static long[] partial(Att a, long length) {
		if (a.origin < 0) {
			return new long[] {0, length};
		}
		long off = Math.min(a.origin, length);
		long len = Math.min(a.count, length - off);
		return new long[] {off, len};
	}

	private static Piece literal(String name, long length, Opener opener, Att a) {
		return out -> {
			long[] r = partial(a, length);
			out.write(name + " ");
			try (InputStream in = opener.open()) {
				if (r[0] > 0) {
					skipFully(in, r[0]);
				}
				out.literal(r[1], in, false);
			}
		};
	}

	/** HEADER.FIELDS: the matching (or, with {@code not}, the other) fields, then a blank line. */
	static byte[] filterFields(byte[] header, List<String> fields, boolean not) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int i = 0;
		boolean include = false;
		while (i < header.length) {
			int end = i;
			while (end < header.length && header[end] != '\n') {
				end++;
			}
			int next = Math.min(header.length, end + 1);
			int contentEnd = end > i && header[end - 1] == '\r' ? end - 1 : end;
			if (contentEnd == i) {
				break; // the blank line
			}
			byte first = header[i];
			if (first != ' ' && first != '\t') {
				int colon = i;
				while (colon < contentEnd && header[colon] != ':') {
					colon++;
				}
				String field = new String(header, i, colon - i, StandardCharsets.ISO_8859_1).trim().toUpperCase(Locale.ROOT);
				include = fields.contains(field) != not;
			}
			if (include) {
				out.write(header, i, contentEnd - i);
				out.write('\r');
				out.write('\n');
			}
			i = next;
		}
		out.write('\r');
		out.write('\n');
		return out.toByteArray();
	}

	/** True if any item sets \Seen. */
	static boolean setsSeen(List<Att> atts) {
		for (Att a : atts) {
			if (a.setsSeen()) {
				return true;
			}
		}
		return false;
	}

	static Set<String> seen() {
		return Set.of(IMAP.SEEN);
	}

	/** Skip exactly {@code n} bytes (InputStream.skipNBytes needs Java 12). */
	static void skipFully(InputStream in, long n) throws IOException {
		while (n > 0) {
			long k = in.skip(n);
			if (k <= 0) {
				if (in.read() < 0) {
					throw new java.io.EOFException("Content ended early");
				}
				k = 1;
			}
			n -= k;
		}
	}
}
