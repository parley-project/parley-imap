package us.bringardner.parley.imap.server.commands;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import us.bringardner.parley.net.server.ICommandProcessor;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.imap.server.ImapCommand;
import us.bringardner.parley.imap.server.ImapRequest;
import us.bringardner.parley.imap.server.ImapRequestProcessor;
import us.bringardner.parley.mail.store.MailStore;

/**
 * Base for IMAP commands: by default valid in the authenticated and selected
 * states, needing the READ permission.
 */
public abstract class BaseCommand implements ImapCommand, IMAP {

	private static final long serialVersionUID = 1L;

	protected static final Set<State> ANY = EnumSet.of(State.NOT_AUTHENTICATED, State.AUTHENTICATED, State.SELECTED);
	protected static final Set<State> NOT_AUTHENTICATED = EnumSet.of(State.NOT_AUTHENTICATED);
	protected static final Set<State> AUTHENTICATED = EnumSet.of(State.AUTHENTICATED, State.SELECTED);
	protected static final Set<State> SELECTED = EnumSet.of(State.SELECTED);

	private String name;
	private String help;
	private final Set<State> states;

	protected BaseCommand(String command, Set<State> states) {
		this.name = command.toUpperCase(Locale.ROOT);
		this.help = "No help available for " + name;
		this.states = states;
	}

	protected BaseCommand(String command) {
		this(command, AUTHENTICATED);
	}

	@Override
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String getHelp() {
		return help;
	}

	public void setHelp(String help) {
		this.help = help;
	}

	@Override
	public void execute(ICommandProcessor processor, IRequestContext context) throws IOException {
		execute((ImapRequestProcessor) processor, (ImapRequest) context);
	}

	@Override
	public IPermission getPermission() {
		return READ_PERMISSION;
	}

	@Override
	public boolean isValidIn(State state) {
		return states.contains(state);
	}

	/** Replies NO [NOPERM] and returns false unless the user has WRITE. */
	protected static boolean checkWrite(ImapRequestProcessor p, ImapRequest r) throws IOException {
		if (!p.canWrite()) {
			p.no(r, CODE_NOPERM, "Permission denied");
			return false;
		}
		return true;
	}

	/** Replies NO [READ-ONLY] and returns false if the selected mailbox is read-only. */
	protected static boolean checkReadWrite(ImapRequestProcessor p, ImapRequest r) throws IOException {
		if (p.getView().isReadOnly()) {
			p.no(r, p.canWrite() ? CODE_CANNOT : CODE_NOPERM, "The mailbox is read-only");
			return false;
		}
		return true;
	}

	/** The store, for brevity. */
	protected static MailStore store(ImapRequestProcessor p) {
		return p.getStore();
	}
}
