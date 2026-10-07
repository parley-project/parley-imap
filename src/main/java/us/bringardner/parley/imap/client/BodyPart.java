package us.bringardner.parley.imap.client;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import us.bringardner.parley.mail.EncodedWord;

/**
 * One node of a BODYSTRUCTURE (RFC 9051 section 7.5.2): a MIME part with its
 * part number for fetching ("1", "2.1", ...), type, parameters, size and
 * disposition. Multipart nodes have children; message/rfc822 parts have the
 * attached message's envelope and structure.
 */
public final class BodyPart {

	String partNumber = "";
	String type = "TEXT";
	String subtype = "PLAIN";
	Map<String, String> parameters = new LinkedHashMap<>();
	String id;
	String description;
	String encoding = "7BIT";
	long size;
	long lines = -1;
	String disposition;
	Map<String, String> dispositionParameters = new LinkedHashMap<>();
	String language;
	String location;
	List<BodyPart> children = new ArrayList<>();
	Envelope attachedEnvelope;

	/** The part number to fetch this part with ("" for the whole message). */
	public String getPartNumber() {
		return partNumber;
	}

	/** e.g. "text/plain", in lower case. */
	public String getMimeType() {
		return (type + "/" + subtype).toLowerCase(Locale.ROOT);
	}

	public String getType() {
		return type;
	}

	public String getSubtype() {
		return subtype;
	}

	public boolean isMultipart() {
		return "MULTIPART".equalsIgnoreCase(type);
	}

	public boolean isAttachedMessage() {
		return "MESSAGE".equalsIgnoreCase(type) && ("RFC822".equalsIgnoreCase(subtype) || "GLOBAL".equalsIgnoreCase(subtype));
	}

	/** Content-Type parameters by lower-case name (RFC 2231 values decoded). */
	public Map<String, String> getParameters() {
		return Collections.unmodifiableMap(parameters);
	}

	public String getCharset() {
		return parameters.get("charset");
	}

	public String getId() {
		return id;
	}

	public String getDescription() {
		return description;
	}

	/** Content-Transfer-Encoding in upper case, e.g. "BASE64". */
	public String getEncoding() {
		return encoding;
	}

	/** Size in bytes, as transferred (still encoded). */
	public long getSize() {
		return size;
	}

	/** Lines of a text part, or -1. */
	public long getLines() {
		return lines;
	}

	/** "attachment", "inline" (lower case), or null. */
	public String getDisposition() {
		return disposition;
	}

	public String getLanguage() {
		return language;
	}

	public String getLocation() {
		return location;
	}

	/** The file name: the disposition's filename, else the type's name parameter; or null. */
	public String getFilename() {
		String f = dispositionParameters.get("filename");
		if (f == null) {
			f = parameters.get("name");
		}
		return f;
	}

	/** True for "attachment", or a named non-text part that isn't marked inline. */
	public boolean isAttachment() {
		if ("attachment".equals(disposition)) {
			return true;
		}
		return !"inline".equals(disposition) && !isMultipart() && (getFilename() != null || isAttachedMessage());
	}

	public List<BodyPart> getChildren() {
		return Collections.unmodifiableList(children);
	}

	/** The envelope of an attached message (message/rfc822), or null. */
	public Envelope getAttachedEnvelope() {
		return attachedEnvelope;
	}

	/** This part and all parts below it, depth first. */
	public List<BodyPart> flatten() {
		List<BodyPart> ret = new ArrayList<>();
		collect(this, ret);
		return ret;
	}

	private static void collect(BodyPart p, List<BodyPart> out) {
		out.add(p);
		for (BodyPart c : p.children) {
			collect(c, out);
		}
	}

	/** The first text/plain (or else text/html) part that isn't an attachment, or null. */
	public BodyPart findText(String subtype) {
		for (BodyPart p : flatten()) {
			if (p.type.equalsIgnoreCase("TEXT") && p.subtype.equalsIgnoreCase(subtype) && !p.isAttachment()) {
				return p;
			}
		}
		return null;
	}

	/** The charset to decode this part with (UTF-8 if none or unknown). */
	public Charset charset() {
		String cs = getCharset();
		if (cs != null) {
			try {
				return Charset.forName(cs);
			} catch (RuntimeException e) {
				// fall through
			}
		}
		return StandardCharsets.UTF_8;
	}

	// ------------------------------------------------------------------ parsing

	/** Parse a BODYSTRUCTURE (or BODY) of a whole message. */
	static BodyPart parse(Token t) {
		BodyPart root = parse(t, "", true);
		return root;
	}

	/**
	 * @param prefix  the part number prefix of this node's children
	 * @param message true if this node is a whole message (its single body is part "1")
	 */
	private static BodyPart parse(Token t, String number, boolean message) {
		if (!t.isList() || t.items().isEmpty()) {
			throw new ImapProtocolException("Invalid BODYSTRUCTURE " + t);
		}
		List<Token> i = t.items();
		BodyPart p = new BodyPart();
		if (i.get(0).isList()) {
			// multipart: children, subtype, [params disposition language location]
			p.type = "MULTIPART";
			p.partNumber = number;
			int k = 0;
			int n = 1;
			while (k < i.size() && i.get(k).isList()) {
				p.children.add(parse(i.get(k), child(number, n++), false));
				k++;
			}
			p.subtype = k < i.size() ? upper(i.get(k++).text()) : "MIXED";
			if (k < i.size()) {
				p.parameters = params(i.get(k++));
			}
			if (k < i.size()) {
				disposition(p, i.get(k++));
			}
			if (k < i.size()) {
				p.language = language(i.get(k++));
			}
			if (k < i.size()) {
				p.location = i.get(k).text();
			}
			return p;
		}
		p.partNumber = message ? "1" : number;
		p.type = upper(i.get(0).text());
		p.subtype = i.size() > 1 ? upper(i.get(1).text()) : "";
		if (i.size() > 2) {
			p.parameters = params(i.get(2));
		}
		p.id = i.size() > 3 ? i.get(3).text() : null;
		p.description = i.size() > 4 && !i.get(4).isNil() ? EncodedWord.decode(i.get(4).text()) : null;
		p.encoding = i.size() > 5 && i.get(5).text() != null ? upper(i.get(5).text()) : "7BIT";
		p.size = i.size() > 6 && i.get(6).isAtom() ? i.get(6).number() : 0;
		int k = 7;
		if (p.isAttachedMessage() && i.size() > 9 && i.get(7).isList() && i.get(8).isList()) {
			p.attachedEnvelope = Envelope.parse(i.get(7));
			BodyPart inner = parse(i.get(8), p.partNumber, false);
			if (inner.isMultipart()) {
				p.children.add(inner);
			} else {
				inner.partNumber = child(p.partNumber, 1);
				p.children.add(inner);
			}
			p.lines = i.get(9).isAtom() ? i.get(9).number() : -1;
			k = 10;
		} else if (p.type.equals("TEXT") && i.size() > 7 && i.get(7).isAtom()) {
			p.lines = i.get(7).number();
			k = 8;
		}
		// extension data: md5, disposition, language, location
		k++; // md5
		if (k < i.size()) {
			disposition(p, i.get(k++));
		}
		if (k < i.size()) {
			p.language = language(i.get(k++));
		}
		if (k < i.size()) {
			p.location = i.get(k).text();
		}
		return p;
	}

	private static String child(String number, int n) {
		return number.isEmpty() ? String.valueOf(n) : number + "." + n;
	}

	private static String upper(String s) {
		return s == null ? "" : s.toUpperCase(Locale.ROOT);
	}

	private static String language(Token t) {
		if (t.isList()) {
			List<String> l = new ArrayList<>();
			for (Token x : t.items()) {
				l.add(x.text());
			}
			return String.join(", ", l);
		}
		return t.text();
	}

	private static void disposition(BodyPart p, Token t) {
		if (t.isList() && !t.items().isEmpty()) {
			String d = t.items().get(0).text();
			p.disposition = d == null ? null : d.toLowerCase(Locale.ROOT);
			if (t.items().size() > 1) {
				p.dispositionParameters = params(t.items().get(1));
			}
		}
	}

	/** Parameters; "name*" values (RFC 2231: charset'lang'%XX) and encoded words are decoded. */
	static Map<String, String> params(Token t) {
		Map<String, String> ret = new LinkedHashMap<>();
		if (!t.isList()) {
			return ret;
		}
		List<Token> i = t.items();
		for (int k = 0; k + 1 < i.size(); k += 2) {
			String name = i.get(k).text();
			String value = i.get(k + 1).text();
			if (name == null || value == null) {
				continue;
			}
			name = name.toLowerCase(Locale.ROOT);
			if (name.endsWith("*")) {
				name = name.substring(0, name.length() - 1);
				value = decode2231(value);
			} else {
				value = EncodedWord.decode(value);
			}
			ret.put(name, value);
		}
		return ret;
	}

	static String decode2231(String v) {
		int q1 = v.indexOf('\'');
		int q2 = q1 < 0 ? -1 : v.indexOf('\'', q1 + 1);
		if (q2 < 0) {
			return v;
		}
		String cs = v.substring(0, q1);
		try {
			return URLDecoder.decode(v.substring(q2 + 1).replace("+", "%2B"), cs.isEmpty() ? "UTF-8" : cs);
		} catch (Exception e) {
			return v;
		}
	}

	@Override
	public String toString() {
		return (partNumber.isEmpty() ? "" : partNumber + " ") + getMimeType() + " " + size + (getFilename() == null ? "" : " " + getFilename());
	}
}
