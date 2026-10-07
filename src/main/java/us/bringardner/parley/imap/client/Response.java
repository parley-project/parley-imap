package us.bringardner.parley.imap.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One server response (RFC 9051 section 7): a continuation request ("+"), a
 * status response (tagged or untagged OK, NO, BAD, BYE, PREAUTH, with an
 * optional [code]), or untagged data ("* 3 EXISTS", "* LIST ...").
 */
public final class Response {

	/** "*" for untagged, "+" for a continuation request, otherwise the command's tag. */
	String tag;
	/** OK, NO, BAD, BYE or PREAUTH for status responses, else null. */
	String status;
	/** The response code inside [ ], e.g. "UIDNEXT 5", or null. */
	String code;
	/** The human-readable text of a status or continuation response. */
	String text = "";
	/** The data of an untagged data response. */
	final List<Token> tokens = new ArrayList<>();

	public String getTag() {
		return tag;
	}

	public boolean isUntagged() {
		return "*".equals(tag);
	}

	public boolean isContinuation() {
		return "+".equals(tag);
	}

	public boolean isStatus() {
		return status != null;
	}

	public String getStatus() {
		return status;
	}

	public boolean isOk() {
		return "OK".equals(status);
	}

	public String getCode() {
		return code;
	}

	/** The first word of the response code in upper case (e.g. "UIDNEXT"), or null. */
	public String getCodeName() {
		return code == null ? null : code.split(" ", 2)[0].toUpperCase(Locale.ROOT);
	}

	/** The response code's arguments (after its name), or "". */
	public String getCodeArgs() {
		if (code == null) {
			return "";
		}
		String[] p = code.split(" ", 2);
		return p.length > 1 ? p[1].trim() : "";
	}

	public String getText() {
		return text;
	}

	public List<Token> getTokens() {
		return tokens;
	}

	/**
	 * The name of an untagged data response: "EXISTS" for "* 3 EXISTS", "LIST" for
	 * "* LIST ..." (upper case), or null.
	 */
	public String getName() {
		if (tokens.isEmpty()) {
			return null;
		}
		Token first = tokens.get(0);
		if (first.isAtom() && isNumber(first.text()) && tokens.size() > 1 && tokens.get(1).isAtom()) {
			return tokens.get(1).text().toUpperCase(Locale.ROOT);
		}
		return first.isAtom() ? first.text().toUpperCase(Locale.ROOT) : null;
	}

	/** The number of "* n NAME" responses, or -1. */
	public long getNumber() {
		if (!tokens.isEmpty() && tokens.get(0).isAtom() && isNumber(tokens.get(0).text())) {
			return tokens.get(0).number();
		}
		return -1;
	}

	static boolean isNumber(String s) {
		if (s == null || s.isEmpty() || s.length() > 19) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) < '0' || s.charAt(i) > '9') {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		if (status != null) {
			return tag + " " + status + (code == null ? "" : " [" + code + "]") + " " + text;
		}
		if (isContinuation()) {
			return "+ " + text;
		}
		StringBuilder sb = new StringBuilder(tag);
		for (Token t : tokens) {
			sb.append(' ').append(t);
		}
		return sb.toString();
	}
}
