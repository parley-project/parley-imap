package us.bringardner.parley.imap.client;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/** What a message list shows: UID, flags, dates, size, envelope and MIME structure. */
public final class MessageSummary {

	long msn;
	long uid = -1;
	Set<String> flags = Collections.emptySet();
	Instant internalDate;
	long size = -1;
	Envelope envelope;
	BodyPart structure;

	/** The message sequence number at the time of the fetch. */
	public long getMsn() {
		return msn;
	}

	public long getUid() {
		return uid;
	}

	public Set<String> getFlags() {
		return flags;
	}

	public boolean hasFlag(String flag) {
		for (String f : flags) {
			if (f.equalsIgnoreCase(flag)) {
				return true;
			}
		}
		return false;
	}

	public boolean isSeen() {
		return hasFlag("\\Seen");
	}

	public boolean isFlagged() {
		return hasFlag("\\Flagged");
	}

	public boolean isDeleted() {
		return hasFlag("\\Deleted");
	}

	public boolean isAnswered() {
		return hasFlag("\\Answered");
	}

	/** When the server received the message. */
	public Instant getInternalDate() {
		return internalDate;
	}

	public long getSize() {
		return size;
	}

	public Envelope getEnvelope() {
		return envelope;
	}

	public BodyPart getStructure() {
		return structure;
	}

	/** True if the structure has an attachment. */
	public boolean hasAttachments() {
		if (structure == null) {
			return false;
		}
		for (BodyPart p : structure.flatten()) {
			if (p.isAttachment()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return "#" + msn + " uid=" + uid + " " + flags + " " + (envelope == null ? "" : envelope.getSubject());
	}
}
