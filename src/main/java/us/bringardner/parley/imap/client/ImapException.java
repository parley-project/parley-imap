package us.bringardner.parley.imap.client;

import java.io.IOException;

/**
 * A command the server refused: a tagged NO or BAD (RFC 9051 section 7.1),
 * with its response code (e.g. "TRYCREATE", "AUTHENTICATIONFAILED") if any.
 */
public class ImapException extends IOException {

	private static final long serialVersionUID = 1L;

	private final String status;
	private final String code;
	private final String serverText;

	public ImapException(String status, String code, String serverText, String command) {
		super(command + " failed: " + status + (code == null ? "" : " [" + code + "]") + " " + serverText);
		this.status = status;
		this.code = code;
		this.serverText = serverText;
	}

	/** "NO" or "BAD". */
	public String getStatus() {
		return status;
	}

	/** The response code (the first word inside [ ]), or null. */
	public String getCode() {
		return code;
	}

	/** True if the code is {@code code} (case-insensitive). */
	public boolean hasCode(String code) {
		return this.code != null && this.code.split(" ")[0].equalsIgnoreCase(code);
	}

	public String getServerText() {
		return serverText;
	}
}
