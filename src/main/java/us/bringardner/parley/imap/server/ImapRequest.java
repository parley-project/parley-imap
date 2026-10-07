package us.bringardner.parley.imap.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * A parsed IMAP command: tag, command name and arguments. It implements the
 * framework's IRequestContext (as the FTP and POP3 command lines do), with
 * tokens that can be atoms, strings, literals or parenthesized lists.
 */
public class ImapRequest implements IRequestContext {

	private static final long serialVersionUID = 1L;

	private final String tag;
	private final String name;
	private final List<ImapToken> args;
	private String commandLine;
	private int pos;
	private final transient List<FileSource> tempFiles;
	private boolean uid;

	public ImapRequest(String tag, String name, List<ImapToken> args, String commandLine, List<FileSource> tempFiles) {
		this.tag = tag;
		this.name = name.toUpperCase(Locale.ROOT);
		this.args = args;
		this.commandLine = commandLine;
		this.tempFiles = tempFiles;
	}

	/** A sub-command of UID (e.g. "UID FETCH ..." becomes "FETCH ..." with the same tag). */
	public ImapRequest subCommand() {
		ImapToken first = args.get(0);
		ImapRequest ret = new ImapRequest(tag, first.text(), new ArrayList<>(args.subList(1, args.size())), commandLine,
				tempFiles);
		ret.uid = true;
		return ret;
	}

	/** True for the sub-command of UID: sequence sets are UIDs. */
	public boolean isUid() {
		return uid;
	}

	public String getTag() {
		return tag;
	}

	/** The command name in upper case. */
	public String getName() {
		return name;
	}

	public List<ImapToken> getArgs() {
		return args;
	}

	/** Temp files holding large literals; deleted after the command. */
	public List<FileSource> getTempFiles() {
		return tempFiles;
	}

	// ------------------------------------------------------------------ argument cursor

	@Override
	public boolean hasNext() {
		return pos < args.size();
	}

	public ImapToken next() {
		if (!hasNext()) {
			throw new ImapParseException("Missing argument for " + name);
		}
		return args.get(pos++);
	}

	public ImapToken peek() {
		return hasNext() ? args.get(pos) : null;
	}

	/** An atom (case is kept). */
	public String nextAtom() {
		ImapToken t = next();
		if (!t.isAtom()) {
			throw new ImapParseException("Expected an atom for " + name);
		}
		return t.text();
	}

	/** An astring: an atom, quoted string or literal (RFC 9051 "astring"). */
	public String nextAstring() {
		ImapToken t = next();
		if (t.isList()) {
			throw new ImapParseException("Expected a string for " + name);
		}
		return t.text();
	}

	public ImapToken.ParenList nextList() {
		ImapToken t = next();
		if (!t.isList()) {
			throw new ImapParseException("Expected a parenthesized list for " + name);
		}
		return (ImapToken.ParenList) t;
	}

	/** Fails unless every argument has been used. */
	public void end() {
		if (hasNext()) {
			throw new ImapParseException("Too many arguments for " + name);
		}
	}

	// ------------------------------------------------------------------ IRequestContext

	@Override
	@Deprecated
	public String getSeperator() {
		return " ";
	}

	@Override
	@Deprecated
	public void setSeperator(String seperator) {
		// fixed: IMAP uses exactly one space
	}

	@Override
	public void setCommandLine(String commandLine) {
		this.commandLine = commandLine;
	}

	/** The command as received, with literals shown as {n}; for logging. */
	@Override
	public String getCommandLine() {
		return commandLine;
	}

	/** The command name (the framework's command factory looks commands up by it). */
	@Override
	public String getFirstToken() {
		pos = 0;
		return name;
	}

	@Override
	public String getNextToken() {
		return hasNext() ? next().text() : null;
	}

	@Override
	public String getRemainingTokens() {
		StringBuilder sb = new StringBuilder();
		while (hasNext()) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(next().text());
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	@Override
	public String[] getTokens() {
		String[] ret = new String[args.size() + 1];
		ret[0] = name;
		for (int i = 0; i < args.size(); i++) {
			ret[i + 1] = args.get(i).text();
		}
		return ret;
	}

	@Override
	public String peekNext() {
		return hasNext() ? args.get(pos).text() : null;
	}

	@Override
	public String toString() {
		return tag + " " + commandLine;
	}
}
