package us.bringardner.parley.imap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The mailbox currently selected, kept up to date from the server's responses
 * (EXISTS, EXPUNGE, FETCH FLAGS), including the UID of each message number when
 * known. Read it from any thread; the client updates it.
 */
public final class SelectedMailbox {

	private final String name;
	volatile boolean readOnly;
	volatile long exists;
	volatile long uidValidity = -1;
	volatile long uidNext = -1;
	volatile Set<String> flags = Collections.emptySet();
	volatile Set<String> permanentFlags = Collections.emptySet();
	/** UID by message sequence number - 1; -1 where not known yet. */
	private final List<Long> uids = new ArrayList<>();

	SelectedMailbox(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	/** EXAMINE, or the server only allows reading. */
	public boolean isReadOnly() {
		return readOnly;
	}

	/** The number of messages. */
	public long getExists() {
		return exists;
	}

	public long getUidValidity() {
		return uidValidity;
	}

	public long getUidNext() {
		return uidNext;
	}

	/** The flags defined in the mailbox (FLAGS response). */
	public Set<String> getFlags() {
		return flags;
	}

	/** The flags the client may change; contains "\*" if new keywords may be created. */
	public Set<String> getPermanentFlags() {
		return permanentFlags;
	}

	/** The UID of a message sequence number (1-based), or -1 if not known. */
	public synchronized long getUid(long msn) {
		return msn < 1 || msn > uids.size() ? -1 : uids.get((int) msn - 1);
	}

	/** The message sequence number of a UID, or -1. */
	public synchronized long getMsn(long uid) {
		int i = uids.indexOf(uid);
		return i < 0 ? -1 : i + 1;
	}

	/** The known UIDs in sequence order (-1 for unknown ones). */
	public synchronized List<Long> getUids() {
		return new ArrayList<>(uids);
	}

	/** The sequence number of the first message whose UID isn't known yet, or -1. */
	synchronized long firstUnknown() {
		int i = uids.indexOf(-1L);
		return i < 0 ? -1 : i + 1;
	}

	synchronized void setExists(long n) {
		exists = n;
		while (uids.size() < n) {
			uids.add(-1L);
		}
		while (uids.size() > n) {
			uids.remove(uids.size() - 1);
		}
	}

	synchronized long expunge(long msn) {
		long uid = -1;
		if (msn >= 1 && msn <= uids.size()) {
			uid = uids.remove((int) msn - 1);
		}
		exists = Math.max(0, exists - 1);
		return uid;
	}

	synchronized void setUid(long msn, long uid) {
		if (msn < 1) {
			return;
		}
		while (uids.size() < msn) {
			uids.add(-1L);
		}
		uids.set((int) msn - 1, uid);
		if (uid >= uidNext) {
			uidNext = uid + 1;
		}
	}

	synchronized void setUids(List<Long> list) {
		uids.clear();
		uids.addAll(list);
		exists = list.size();
	}

	static Set<String> set(List<String> flags) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(flags));
	}

	@Override
	public String toString() {
		return name + " exists=" + exists + " uidvalidity=" + uidValidity + " uidnext=" + uidNext + (readOnly ? " read-only" : "");
	}
}
