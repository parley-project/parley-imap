package us.bringardner.parley.mail.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Sharing;

/**
 * For tests: another server on the same store, with a lock service and Mailbox objects of its
 * own (as in another JVM), reaching the package's own ways in.
 */
public final class AnotherServer implements AutoCloseable {

	private final Locks locks;
	private final LockDomain domain;

	public AnotherServer(FileSource root, String id) throws IOException {
		locks = Locks.open(LockOptions.sharing(Sharing.CLUSTER).withOwnerId(id).withLease(Duration.ofSeconds(2), Duration.ofMillis(400))
				.withTimeout(Duration.ofSeconds(20)));
		domain = locks.domain("mail", root);
	}

	/** This server's Mailbox object for a directory. */
	public Mailbox open(FileSource dir) throws IOException {
		if (!dir.isDirectory()) {
			dir.mkdirs();
		}
		return Mailbox.open(dir, MailboxRegistry.keyOf(dir), domain, MailboxRegistry.lockName("mailbox", domain, dir));
	}

	/** Delete a mailbox directory and everything in it. */
	public void deleteMailbox(FileSource dir) throws IOException {
		MailStore.deleteTree(dir);
	}

	public MessageInfo append(Mailbox m, String text) throws IOException {
		return m.append(new ByteArrayInputStream(("Subject: " + text + "\r\n\r\n" + text + "\r\n").getBytes(StandardCharsets.UTF_8)),
				Set.of(), 0);
	}

	@Override
	public void close() {
		locks.close();
	}
}
