# Changelog

## parley-imap 2.0.0 (unreleased)

### Changed (2026-11-06: users, logins and permissions come from parley-iam)

- **parley-imap uses parley-iam and the new parley-net**: users, logins and permissions come from the server's
  parley-iam `UserRegistry` (`authenticationProvider`, `parley.mail.users`, or `setUserRegistry`). With no registry a
  login is a configuration error (`IllegalStateException` naming the setting), not a refused login.
- **`ImapCommand.READ_PERMISSION` / `WRITE_PERMISSION` are `ParleyPermission`s** (`read`, `write` on the server as a
  whole); `BaseCommand.getPermission()` returns `ParleyPermission`. The registry decides them.
- **A registry that can't answer is `NO [UNAVAILABLE]`** (RFC 5530) for a login, a permission check, a user lookup
  (Other Users, SETACL) or a group lookup; never `NO [AUTHENTICATIONFAILED]` or a denial, and not counted.
  `canRead()` and `canWrite()` now throw `UserRegistryUnavailableException` (an `IOException`).
- **Logins go through the session's `authenticate`** (parley-mail's `authenticate(user, password)`):
  - `LOGIN` and `AUTHENTICATE PLAIN` check the password with the registry, then `read`, then open the mail store; a
    session that can't finish logging in is logged out again.
  - `loginVerified(String)` is now `loginVerified(ImapSaslAuthenticator, String)`: SCRAM is verified by the
    authenticator that ran the exchange.
  - `ImapSaslAuthenticator` extends parley-mail's `MailSaslAuthenticator` (PLAIN, SCRAM-SHA-256, SCRAM-SHA-1);
    `newSaslAuthenticator()` returns one. A failed exchange that checked no password goes through `exchangeFailed`,
    so a wrong SCRAM proof is counted.
- **Failed logins are counted and delayed by parley-net** (`loginFailureDelay`, `maxLoginAttempts`); IMAP's own
  counting and delays are removed. After the last one the session still says `* BYE Too many failed logins`
  (`replyTooManyLoginFailures`). A user without `read` (`NO [AUTHORIZATIONFAILED]`) is now counted as a failed login,
  as in POP3.
- **`ImapRequestProcessor.logout()` is now `endSession()`** (LOGOUT, too many failed logins): parley-net's session has
  a final `logout()` of its own, which logs the user out; `endSession()` calls it.
- **Groups for `$group` ACL entries** come from parley-mail's `AbstractMailServer.findGroupNames` (the user registry's
  `findGroups`), read once a session (`groupNames()`). The mailbox ACL rules are unchanged: groups
  with their domain, `anyone`, the owner keeps `l` and `a`, the `.imap-shares` index.
- **The `maildrop` setting is a principal attribute**; `ImapServer.getInboxDirectory`, `getMailStore` and
  `openMailStore` take a `ParleyPrincipal`.
- **Tests** use `FileUserRegistry` and `setUserRegistry`; new tests cover counting of wrong SCRAM proofs and
  `NO [UNAVAILABLE]` for permission and user lookups after login.


### Changed (2026-11-03: To-Do 15.3)

- **`main` registers each server's store bean**: while they run, the servers `main` runs have their stores as
  `us.bringardner.parley:type=MailStore,name=<id>` (imap, imaps) on the platform MBean server (parley-mail's
  `MailServerMain`), so their mailbox ACLs can be managed without an application.

### Changed (2026-11-03: the launcher moved to parley-mail-admin)

- **`ImapProviders` (the providers imap, imaps) moved to parley-mail-admin** with the launcher, which runs the mail suite; this module
  registers no server providers. Its `main` runs its servers alone in one process with parley-net's `ServerMain.run`
  (its settings file and the command line, as system properties; the implicit TLS server only when there is a TLS
  key store), not through the launcher.

### Changed (2026-11-02: COPY and MOVE flags, To-Do 3.6)

- **COPY and MOVE make the copies with only the flags the rights on the target allow** (RFC 4314 section 4), through
  parley-mail's `copyTo` / `moveTo` with a flag filter: a flag the user may not set never reaches the target. They were
  copied with every flag and the others taken off afterwards.

### Added (2026-11-02: mailbox ACLs, To-Do 3.4)

- **RFC 4314 ACLs**: the ACL and RIGHTS=texk capabilities, SETACL, DELETEACL, GETACL, LISTRIGHTS and MYRIGHTS, and the
  rights each command needs (see the README's "Mailbox access control"). The server's READ/WRITE permissions are a
  ceiling; ADMIN gives no special rights.
- **The Other Users namespace** (RFC 2342): `Other Users/<user>/<mailbox>`, `Other Users/<user>` being the user's INBOX.
  LIST shows another user's mailbox only where the user has `l`, found through parley-mail's share index.
  `ImapServer.openMailStore(principal)`, `getShareIndex()`; `ImapRequestProcessor.ref(name)`, `rights(ref)`,
  `select(ref, rights, readOnly)`; `Status.statusLine(p, ref, items)`.

### Changed (2026-11-02: mailbox ACLs, To-Do 3.4)

- **WRITE alone no longer decides** what a session may change: the mailbox's rights do, limited by READ and WRITE.
  CREATE, DELETE, RENAME, APPEND and COPY without the right get `NO [NOPERM]` (or `NO [NONEXISTENT]` for a mailbox
  the user can't see), as before for a user without WRITE.
- NAMESPACE answers `(("" "/")) (("Other Users/" "/")) NIL`. A personal mailbox already named `Other Users` (or under
  it) stays the user's own; new names there are in the Other Users namespace.

### Changed (2026-11-02: durations and credentials, To-Do 2.19)

- **`<prefix>.autologout` is a duration** (`30m`, `1h`; a bare number is still ms), read by parley-mail.
- **ImapCli's `--password` names where the password is kept** (`@env:NAME`, `@keychain:NAME`, `@file:PATH`; parley-files'
  credentials, resolved as IMAP's, `parley.credentials.imap.*`), never the password: a literal one is refused, since it
  shows in process listings. `$IMAP_PASSWORD` is no longer read (`--password @env:IMAP_PASSWORD` does the same).
  Without `--password` the password is asked for, as before.

### Changed (2026-11-01: parley.servers says which servers run, To-Do 2.12)

- **`imaps` runs on 993 when `parley.servers` lists it** and it has a TLS key store (skipped with a warning while it
  has none); `parley.mail.imaps.port` is only its port. **`parley.mail.imap.secure` is no longer read** (a warning
  says so): it turned IMAP off and IMAPS on, two settings for one thing. `ImapServer.main` runs `imap` and `imaps`.

### Changed (2026-10-26: each server alone, shared assets carry their own settings)

- **Each IMAP server reads its own settings under its own prefix**: `autologout`, `requireTls`, `appendLimit` and
  `defaultMailboxes` are `parley.mail.imaps.*` for IMAPS (they used to be read as `parley.mail.imap.*` for both).
  The providers make each server from its own configuration.

### Added

- **The settings this module reads are listed** in `META-INF/parley/settings.list`, so a Parley server's
  start-up check (parley-net's `KnownSettings`, To-Do 2.16) knows them and warns only about a setting
  nothing reads. A test checks the list has every name the code here reads.
- **`ImapProviders`**: parley-net `ServerProvider`s `imap` and `imaps` (implicit TLS), so IMAP runs alone or with other
  servers in one process (parley-net's `ServerMain`), and is managed with JMX. `ImapServer.main` runs them with
  `ServerMain.launch`: settings from `parley-mail.properties`, then `parley-imap.properties`; `imaps` runs when
  `parley.mail.imaps.port` is set. Users and the TLS key store can come from the suite's `parley.mail.users` and
  `parley.mail.tls.*` (parley-mail).

### Changed

- **`ImapServer.main` runs the server as a service** with parley-net's `ServerMain.run`: settings from the command
  line (which wins) and `parley-imap.properties` in Parley's administrator folder (`/etc/parley`, or the file the server's
  `configFile` property names); it stops on SIGTERM; a failed start now exits with 1.

### Removed (needs a code change)

- `ImapRequest.get/setSeperator`: it implements parley-net 2.0.0's `get/setSeparator`.

### Changed (needs a configuration change)

- **Property names** (Parley 2.0.0; the old names are no longer read): `JImap.*` is `parley.mail.imap.*`
  (`.autologout`, `.requireTls`, `.appendLimit`, `.defaultMailboxes`, `.secure`, `.port`), and the maildrop
  root is the shared `parley.mail.root` / `parley.mail.fileSource` (no more falling back to `JPop3.root`).
  `JImap.properties` is `parley.mail.imap.configFile`.
- A server's own settings are read with its prefix, `parley.mail.imap` (was `ImapServer`): key store, limits and
  access control. A server with implicit TLS uses `parley.mail.imaps` (port `parley.mail.imaps.port`, 993).
- `JImap.loginFailureDelay` is now the server's `loginFailureDelay`: `LOGIN_FAILURE_DELAY_PROP` is gone.

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
