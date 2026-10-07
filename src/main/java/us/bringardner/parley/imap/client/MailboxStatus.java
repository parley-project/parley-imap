package us.bringardner.parley.imap.client;

/** The result of STATUS (RFC 9051 section 6.3.11); -1 for an item that wasn't asked for. */
public final class MailboxStatus {

	String name;
	long messages = -1;
	long uidNext = -1;
	long uidValidity = -1;
	long unseen = -1;
	long deleted = -1;
	long size = -1;

	public String getName() {
		return name;
	}

	public long getMessages() {
		return messages;
	}

	public long getUidNext() {
		return uidNext;
	}

	public long getUidValidity() {
		return uidValidity;
	}

	public long getUnseen() {
		return unseen;
	}

	public long getDeleted() {
		return deleted;
	}

	/** Total size in bytes (STATUS=SIZE), or -1. */
	public long getSize() {
		return size;
	}

	@Override
	public String toString() {
		return name + " messages=" + messages + " unseen=" + unseen + " uidnext=" + uidNext + " uidvalidity=" + uidValidity
				+ (size >= 0 ? " size=" + size : "");
	}
}
