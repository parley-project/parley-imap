# Changelog

## parley-imap 1.1.0 (unreleased)

### Added

- When the access control list can't answer (its database is down), LOGIN and AUTHENTICATE get
  `NO [UNAVAILABLE]` (try again later), not "invalid user name or password", and it isn't counted as a failed
  login (`LoginResult.UNAVAILABLE`). `ImapServer.deliver` fails instead of guessing the user's mail store.
- **SCRAM-SHA-256 and SCRAM-SHA-1** for `AUTHENTICATE`, offered in CAPABILITY when the server's access control list keeps SCRAM
  credentials for every user (parley-net 1.1.0: `FileBasedAcl --scram`, or parley-net-acl-jdbc). The user is
  logged in with `ImapRequestProcessor.loginVerified(user)` after the proof, with the same permission checks as a password login.
- **Several servers on one store** (parley-mail 1.1.0's `parley.mail.sharedStore`): mailbox changes run under
  locks that reach the other servers (parley-net's locks), and a session sees their changes at its next NOOP,
  IDLE check or command.
- If another server deletes and recreates the selected mailbox (a new UIDVALIDITY), the session is sent
  `OK [CLOSED]` and the mailbox is deselected, so the client selects it again and sees the new UIDVALIDITY
  instead of using UIDs that now name other messages. EXPUNGE and CLOSE in that session remove nothing from the
  new mailbox.

### Changed (no code change needed)

- Unsaved flag changes are saved when the server stops, after the sessions end and before the store claim is
  given up (`releasingStore()`), so the store's locks still work.
- Dependencies: parley-mail and parley-net 1.1.0; their tests jars for tests (the shared test users and helpers). No longer parley-pop3, even for
  tests: sharing the maildrop with POP3 is tested against the directory.

### Removed (needs a code change)

- `ImapServer.deliver(user, mailbox, message, flags)`: delivery isn't part of IMAP. Use the user's parley-mail store,
  `MailStore.deliver(content, junk)` (or `Mailbox.append` for another mailbox and flags).

## parley-imap 1.0.0 (2026-10-16)

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

### Added

- `ImapClientConfig.setAuthMechanisms(List)`: SASL mechanisms `ImapClient.login` may use, best first
  (SCRAM-SHA-256, SCRAM-SHA-1, CRAM-MD5, PLAIN, LOGIN). The first one the server offers is used;
  with SCRAM the password is never sent and the server has to prove it knows it, or the connection
  is dropped. The default (empty) keeps AUTHENTICATE PLAIN / LOGIN. A server that advertises SCRAM
  but can't use it for an account fails the login, which is why this is off by default.
- `ImapClient.getCapabilitySet()`: the capabilities with their parameters (`AUTH=PLAIN` is `AUTH`
  with the parameter `PLAIN`).

### Internal

- The session state is a parley-net `StateMachine` (NOT_AUTHENTICATED, AUTHENTICATED, SELECTED,
  LOGOUT, with the legal moves declared), and `ImapCommand` is an `IStatefulCommand` with
  `getValidStates()`. `isValidIn(State)` is still there, now a default method that uses
  `getValidStates()`.
- The CAPABILITY list is built from a parley-net `CapabilityRegistry`, and AUTHENTICATE runs
  through `SaslServerDriver` with `ImapSaslAuthenticator` supplying the login. The replies,
  response codes and the advertised `AUTH=PLAIN SASL-IR` are the same as before. Requires a
  parley-net that has `StateMachine` and the `capability` and `sasl` packages.

### Unchanged

- The `JImap.*` / `ImapServer.*` configuration properties and the mailboxes on disk.
