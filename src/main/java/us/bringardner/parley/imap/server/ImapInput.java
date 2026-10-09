package us.bringardner.parley.imap.server;

import java.io.InputStream;

import us.bringardner.parley.io.BufferedLineInput;


/**
 * Reads the client side of an IMAP connection: command lines (bytes up to CRLF)
 * and literals (exactly n bytes, streamed). A line interrupted by a socket timeout
 * is kept, so reading can resume (used while IDLE waits for "DONE").
 */
public final class ImapInput extends BufferedLineInput {

	public ImapInput(InputStream in) {
		super(in, "Connection closed in a literal", false);
	}
}
