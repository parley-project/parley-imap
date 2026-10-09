package us.bringardner.parley.imap.server;

import java.util.Map;

import us.bringardner.parley.net.server.AbstractCommandFactory;
import us.bringardner.parley.net.server.ICommand;
import us.bringardner.parley.imap.server.commands.Append;
import us.bringardner.parley.imap.server.commands.Authenticate;
import us.bringardner.parley.imap.server.commands.Capability;
import us.bringardner.parley.imap.server.commands.Check;
import us.bringardner.parley.imap.server.commands.Close;
import us.bringardner.parley.imap.server.commands.Copy;
import us.bringardner.parley.imap.server.commands.Create;
import us.bringardner.parley.imap.server.commands.Delete;
import us.bringardner.parley.imap.server.commands.Enable;
import us.bringardner.parley.imap.server.commands.Examine;
import us.bringardner.parley.imap.server.commands.Expunge;
import us.bringardner.parley.imap.server.commands.Fetch;
import us.bringardner.parley.imap.server.commands.Idle;
import us.bringardner.parley.imap.server.commands.Login;
import us.bringardner.parley.imap.server.commands.Logout;
import us.bringardner.parley.imap.server.commands.Lsub;
import us.bringardner.parley.imap.server.commands.Move;
import us.bringardner.parley.imap.server.commands.Namespace;
import us.bringardner.parley.imap.server.commands.Noop;
import us.bringardner.parley.imap.server.commands.Rename;
import us.bringardner.parley.imap.server.commands.Search;
import us.bringardner.parley.imap.server.commands.Select;
import us.bringardner.parley.imap.server.commands.StartTls;
import us.bringardner.parley.imap.server.commands.Status;
import us.bringardner.parley.imap.server.commands.Store;
import us.bringardner.parley.imap.server.commands.Subscribe;
import us.bringardner.parley.imap.server.commands.Uid;
import us.bringardner.parley.imap.server.commands.Unselect;
import us.bringardner.parley.imap.server.commands.Unsubscribe;

/**
 * Maps IMAP command names to their command classes (as FtpCommandFactory does
 * for FTP). Commands can be replaced or added with {@link #addCommand(ICommand)}.
 */
public class ImapCommandFactory extends AbstractCommandFactory {

	private static final long serialVersionUID = 1L;

	private static final Map<String, ICommand> commands = newRegistry();

	static {
		// any state (RFC 9051 section 6.1)
		addCommand(new Capability());
		addCommand(new Noop());
		addCommand(new Logout());
		// not authenticated (6.2)
		addCommand(new StartTls());
		addCommand(new Authenticate());
		addCommand(new Login());
		// authenticated (6.3)
		addCommand(new Enable());
		addCommand(new Select());
		addCommand(new Examine());
		addCommand(new Create());
		addCommand(new Delete());
		addCommand(new Rename());
		addCommand(new Subscribe());
		addCommand(new Unsubscribe());
		addCommand(new us.bringardner.parley.imap.server.commands.List());
		addCommand(new Lsub());
		addCommand(new Namespace());
		addCommand(new Status());
		addCommand(new Append());
		addCommand(new Idle());
		// selected (6.4)
		addCommand(new Check());
		addCommand(new Close());
		addCommand(new Unselect());
		addCommand(new Expunge());
		addCommand(new Search());
		addCommand(new Fetch());
		addCommand(new Store());
		addCommand(new Copy());
		addCommand(new Move());
		addCommand(new Uid());
	}

	public ImapCommandFactory() {
		super(commands);
	}

	public static void addCommand(ICommand cmd) {
		register(commands, cmd);
	}
}
