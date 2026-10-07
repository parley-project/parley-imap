package us.bringardner.parley.imap.server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The modified UTF-7 of IMAP4rev1 mailbox names (RFC 3501 section 5.1.3,
 * RFC 9051 appendix A.1): printable ASCII except "&" stands for itself, "&" is
 * "&-", and anything else is UTF-16 in base64 (with "," for "/") between "&"
 * and "-".
 */
public final class ModifiedUtf7 {

	private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();

	private ModifiedUtf7() {
	}

	public static String encode(String s) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c >= 0x20 && c <= 0x7e) {
				out.append(c == '&' ? "&-" : String.valueOf(c));
				i++;
				continue;
			}
			int j = i;
			while (j < s.length() && (s.charAt(j) < 0x20 || s.charAt(j) > 0x7e)) {
				j++;
			}
			byte[] utf16 = s.substring(i, j).getBytes(StandardCharsets.UTF_16BE);
			out.append('&').append(ENCODER.encodeToString(utf16).replace('/', ',')).append('-');
			i = j;
		}
		return out.toString();
	}

	/**
	 * Decode a modified UTF-7 name. A name that isn't valid modified UTF-7 is
	 * returned unchanged.
	 */
	public static String decode(String s) {
		if (s.indexOf('&') < 0) {
			return s;
		}
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c != '&') {
				out.append(c);
				i++;
				continue;
			}
			int end = s.indexOf('-', i + 1);
			if (end < 0) {
				return s;
			}
			if (end == i + 1) {
				out.append('&');
			} else {
				String b64 = s.substring(i + 1, end).replace(',', '/');
				try {
					byte[] utf16 = Base64.getDecoder().decode(pad(b64));
					if (utf16.length % 2 != 0) {
						return s;
					}
					out.append(new String(utf16, StandardCharsets.UTF_16BE));
				} catch (IllegalArgumentException e) {
					return s;
				}
			}
			i = end + 1;
		}
		return out.toString();
	}

	private static String pad(String b64) {
		int r = b64.length() % 4;
		return r == 0 ? b64 : b64 + "====".substring(r);
	}
}
