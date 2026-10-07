# Changelog

## parley-imap 1.0.0 (unreleased)

The IMAP server and client of BjlEmail (`us.bringardner:bjl_email` 1.0.0-SNAPSHOT, never released)
are now **parley-imap**, part of the Parley library family.

### Changed (needs a code change)

- Maven coordinates: `us.bringardner.parley:parley-imap`.
- Packages: `us.bringardner.net.imap` (and `.client`, `.server`, `.server.commands`) is now
  `us.bringardner.parley.imap`.
- The server's mail store, `us.bringardner.net.imap.server.store`, moved to parley-mail as
  `us.bringardner.parley.mail.store`, so SMTP and POP3 can use it without IMAP.
- `IMAP.INBOX`, `IMAP.DELIMITER`, the special-use attributes (`SENT`, `DRAFTS`, `TRASH`, `JUNK`,
  `ARCHIVE`) and the response codes the store uses are now defined in `MailboxConstants` (parley-mail);
  `IMAP` repeats them with the same values, so `IMAP.INBOX` and friends still work.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.imap`.
- Dependencies: `parley-mail` and `parley-net`; `parley-pop3` for tests only.

### Unchanged

- The `JImap.*` / `ImapServer.*` configuration properties and the mailboxes on disk.
