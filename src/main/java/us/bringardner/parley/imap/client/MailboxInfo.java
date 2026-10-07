package us.bringardner.parley.imap.client;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** A mailbox from LIST (RFC 9051 section 7.3.1): its name, hierarchy delimiter and attributes. */
public final class MailboxInfo {

	private final String name;
	private final String delimiter;
	private final Set<String> attributes;

	MailboxInfo(String name, String delimiter, Set<String> attributes) {
		this.name = name;
		this.delimiter = delimiter;
		this.attributes = Collections.unmodifiableSet(new LinkedHashSet<>(attributes));
	}

	/** The full name, decoded (Unicode). */
	public String getName() {
		return name;
	}

	/** The hierarchy delimiter (e.g. "/"), or null for a flat name space. */
	public String getDelimiter() {
		return delimiter;
	}

	/** The last level of the name (for a tree view). */
	public String getShortName() {
		if (delimiter == null || delimiter.isEmpty()) {
			return name;
		}
		int i = name.lastIndexOf(delimiter);
		return i < 0 ? name : name.substring(i + delimiter.length());
	}

	/** The parent's full name, or null at the top level. */
	public String getParentName() {
		if (delimiter == null || delimiter.isEmpty()) {
			return null;
		}
		int i = name.lastIndexOf(delimiter);
		return i <= 0 ? null : name.substring(0, i);
	}

	/** Attributes such as \Noselect, \HasChildren, \Sent (as sent, with the backslash). */
	public Set<String> getAttributes() {
		return attributes;
	}

	public boolean hasAttribute(String attribute) {
		for (String a : attributes) {
			if (a.equalsIgnoreCase(attribute)) {
				return true;
			}
		}
		return false;
	}

	/** False for \Noselect and \NonExistent mailboxes. */
	public boolean isSelectable() {
		return !hasAttribute("\\Noselect") && !hasAttribute("\\NonExistent");
	}

	public boolean hasChildren() {
		return hasAttribute("\\HasChildren");
	}

	/** The RFC 6154 special use (\Sent, \Drafts, \Trash, \Junk, \Archive, \All, \Flagged), or null. */
	public String getSpecialUse() {
		for (String a : attributes) {
			for (String s : new String[] {"\\Sent", "\\Drafts", "\\Trash", "\\Junk", "\\Archive", "\\All", "\\Flagged"}) {
				if (a.equalsIgnoreCase(s)) {
					return s;
				}
			}
		}
		return null;
	}

	public boolean isInbox() {
		return "INBOX".equalsIgnoreCase(name);
	}

	@Override
	public String toString() {
		return name + " " + attributes;
	}
}
