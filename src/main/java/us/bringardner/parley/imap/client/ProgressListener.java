package us.bringardner.parley.imap.client;

/** Progress of a long transfer (fetching or appending a message), for a progress bar. */
@FunctionalInterface
public interface ProgressListener {

	/**
	 * @param done  bytes transferred so far
	 * @param total bytes in all, or -1 if unknown
	 */
	void progress(long done, long total);
}
