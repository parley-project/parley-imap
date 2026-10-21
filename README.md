# parley-imap

IMAP for **Parley**, a family of Java libraries for implementing internet protocols:

- `us.bringardner.parley.imap.server`: an IMAP server (IMAP4rev2, RFC 9051, also speaking IMAP4rev1),
  built on `parley-net` like the FTP server in `parley-ftp`. It serves the `parley-mail` store, so it
  shares mail with the POP3 server (`parley-pop3`) and receives mail delivered by `parley-smtp`.
- `us.bringardner.parley.imap.client`: an IMAP client library designed to be the mail engine of a
  desktop application, and `ImapCli`, a command-line program built on it.

Requires Java 11 or later. Depends on `parley-mail` and `parley-net` (which bring in `parley-files`,
`parley-core` and `parley-io`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-imap</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-imap was split out of `us.bringardner:bjl_email` (BjlEmail). `us.bringardner.net.imap` is now
> `us.bringardner.parley.imap`. The server's mail store moved to parley-mail
> (`us.bringardner.parley.mail.store`). Property names that start with a class name change with the
> package; the `JImap.*` and `ImapServer.*` properties are unchanged. In 2.0.0 every property was renamed
> (`parley.mail.imap.*`, `parley.mail.root`): see the CHANGELOG.

## Build

```
mvn package
```

Tests run with a 64 MB heap, so message bodies must stay out of memory. The mail protocols don't
depend on each other, not even in tests: that INBOX is the maildrop POP3 shares is tested here
against the directory (parley-mail's format), and parley-pop3 tests its side.

## IMAP server

`ImapServer` follows the same design as `Pop3Server`:

| parley-ftp | parley-imap | Role |
|---|---|---|
| `FtpServer` | `ImapServer` | Accepts connections, holds the configuration |
| `FtpRequestProcessor` | `ImapRequestProcessor` | Runs one session |
| `FtpCommandFactory` | `ImapCommandFactory` | Maps command names to command classes |
| `FtpCommand`, `commands.*` | `ImapCommand`, `commands.*` | One class per command |
| `FTP` | `IMAP` | Protocol constants |

`ImapRequestProcessor` reads the socket itself (`ImapCommandReader`), because IMAP commands can carry literals of any size. Literals over 1 MB go to temp files, so an APPEND never has to fit in memory, and FETCH streams message content from the file.

```java
ImapServer server = new ImapServer();          // port 143
server.setMaildropRoot(rootFileSource);        // the same root as Pop3Server
server.start();
```

Mail is delivered by SMTP, or by your code through the user's parley-mail store (`MailStore.deliver`); IMAP itself
only adds messages with APPEND, COPY and MOVE.

Or from the command line: `java us.bringardner.parley.imap.server.ImapServer -Dparley.mail.imap.port=1143 -Dparley.mail.root=/var/mail/pop3`

### Protocol support

- **IMAP4rev2 (RFC 9051)** for clients that send `ENABLE IMAP4rev2`, and **IMAP4rev1 (RFC 3501)** for the rest. A rev1 session gets `* n RECENT`, `[UNSEEN n]`, the `SEARCH` response, `LSUB` and `CHECK`. A rev2 session gets `ESEARCH`, `LIST` in `SELECT` and `[CLOSED]` responses.
- **Commands:** CAPABILITY, NOOP, LOGOUT, STARTTLS, AUTHENTICATE (PLAIN, SCRAM-SHA-256, SCRAM-SHA-1), LOGIN, ENABLE, SELECT, EXAMINE, CREATE, DELETE, RENAME, SUBSCRIBE, UNSUBSCRIBE, LIST, LSUB, NAMESPACE, STATUS, APPEND, IDLE, CHECK, CLOSE, UNSELECT, EXPUNGE, SEARCH, FETCH, STORE, COPY, MOVE and UID (COPY, FETCH, MOVE, SEARCH, STORE, EXPUNGE).
- **Extensions** (built into IMAP4rev2 and also offered to rev1 clients): SASL-IR, LITERAL+, ENABLE, IDLE, NAMESPACE, UNSELECT, UIDPLUS, ESEARCH, SEARCHRES, LIST-EXTENDED, LIST-STATUS, MOVE, SPECIAL-USE, CHILDREN, BINARY, STATUS=SIZE and UTF8=ACCEPT.
- **FETCH:** FLAGS, UID, INTERNALDATE, RFC822.SIZE, ENVELOPE, BODY, BODYSTRUCTURE, BODY[section]<partial> (HEADER, HEADER.FIELDS[.NOT], TEXT, MIME, part numbers including attached messages), BODY.PEEK, BINARY[part], BINARY.PEEK, BINARY.SIZE, and the rev1 items RFC822, RFC822.HEADER and RFC822.TEXT. Non-PEEK fetches set `\Seen`.
- **SEARCH:** all RFC 9051 keys. Header text is matched after decoding RFC 2047 encoded words. BODY and TEXT search the decoded text of the text parts. `RETURN (MIN MAX COUNT ALL SAVE)` and `$` are supported.
- **Not implemented:** CONDSTORE/QRESYNC, NOTIFY, QUOTA, ACL, METADATA, multi-APPEND, and SASL mechanisms other than PLAIN and SCRAM. SCRAM-SHA-256 and SCRAM-SHA-1 (RFC 5802, 7677) are offered when the access control list keeps SCRAM credentials for every user (parley-net's `FileBasedAcl --scram`, or parley-net-acl-jdbc).

### Mail storage (shared with POP3)

Mail is kept in the `parley-mail` store (`us.bringardner.parley.mail.store`): INBOX is the user's POP3
maildrop, other mailboxes are directories under `<inbox>/.mailboxes`, and each has a `.imap-index`.
See the parley-mail README for the layout. New users get Sent, Drafts, Trash, Junk and Archive;
turn this off with `parley.mail.imap.defaultMailboxes=false`.

Several servers (IMAP, POP3, SMTP, in any number of JVMs) can share a store when
`parley.mail.sharedStore` allows it (`off`, the default, `host` or `cluster`). A session sees other
servers' changes at its next NOOP, IDLE check or command, within a second. If another server deletes
and recreates the selected mailbox (a new UIDVALIDITY), the session gets `OK [CLOSED]` and must
select it again. See "Several servers on one store" in the parley-mail README.

### Users and permissions

Users come from the access control list, set up as for Pop3Server with the `parley.mail.imap.authenticationProvider` property (e.g. `FileBasedAcl` with `parley.mail.imap.userFile`). READ is needed to log in. WRITE is needed for any change: without it, SELECT opens mailboxes read-only, and APPEND, CREATE, DELETE and RENAME get `NO [NOPERM]`. User names and passwords are prepared with SASLprep (RFC 4013), as for POP3.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `parley.mail.imap.port` | 143 | Port (used by `ImapServer.main`) |
| `parley.mail.imap.secure` | false | `main` starts a server with implicit TLS, on `parley.mail.imaps.port` (993) |
| `parley.mail.root` | `/pop3` (`C:/pop3` on Windows) | Maildrop root, shared with POP3 and SMTP |
| `parley.mail.fileSource` | the default factory | FileSource factory for the root |
| `parley.mail.imap.autologout` | 1800000 ms | Idle sessions get `* BYE` and are closed. RFC 9051 requires at least 30 minutes. `setAutologout()` |
| `parley.mail.imap.loginFailureDelay` | 1000 ms | Delay before replying to a failed login. The connection is closed after 3 failures. `setLoginFailureDelay()` |
| `parley.mail.imap.requireTls` | false | Advertise LOGINDISABLED and refuse LOGIN and AUTHENTICATE until STARTTLS. `setRequireTls()` |
| `parley.mail.imap.appendLimit` | 104857600 | Largest message APPEND accepts; larger ones get `NO [TOOBIG]`. `setAppendLimit()` |
| `parley.mail.imap.defaultMailboxes` | true | Create the special-use mailboxes for new users |
| `parley.mail.imap.keyStoreName`, `parley.mail.imap.keyStorePassword`, `parley.mail.imap.keyStoreType` | none | Key store for STARTTLS and implicit TLS. STARTTLS is offered only when one is configured. |
| `parley.mail.imap.*` | | The listener's own settings use its prefix: the key store, the limits of parley-core's `AbstractCoreServer` (`maxConnections`, `maxLoginAttempts`, ...) and the access control list (`authenticationProvider`, `userFile`). A server with implicit TLS uses `parley.mail.imaps`. |

### Internationalized mail

- **UTF-8 sessions:** IMAP4rev2 (and `ENABLE UTF8=ACCEPT`, RFC 6855) sessions get UTF-8 mailbox names and quoted strings, and messages exactly as stored.
- **Other IMAP4rev1 sessions:**
  - Mailbox names are sent and read in modified UTF-7.
  - A message with UTF-8 headers is sent as its RFC 6858 surrogate (made by `Downgrader`, as for POP3) in FETCH, ENVELOPE, BODYSTRUCTURE and RFC822.SIZE. The surrogate is cached in the mailbox's hidden `.imap-cache` directory.
  - SEARCH LARGER/SMALLER and STATUS SIZE use the stored size.
- **APPEND** accepts the RFC 6855 `UTF8 (~{n}...)` form.

### Testing

`TestImapServer` runs both servers over real sockets. It covers:

- IMAP4rev1 and IMAP4rev2 sessions, and two sessions on one mailbox;
- IDLE and STARTTLS;
- UTF-8 handling;
- sharing with POP3;
- index persistence;
- a 70 MB message appended and fetched under the 64 MB test heap.

The server also works with Python's `imaplib` and with `curl` (`imap://`, including `--ssl-reqd`).

User names with non-ASCII characters (like the test user `jösé`) become directory names, so the JVM must use UTF-8 for file names. That is the default on macOS. On Linux, run with a UTF-8 locale (e.g. `LANG=C.UTF-8`).

## IMAP client

`us.bringardner.parley.imap.client` talks to any IMAP server (IMAP4rev2 or IMAP4rev1). The API is built for a desktop application; `ImapCli` is a command-line program on top of it.

### Using the API

```java
ImapClientConfig config = new ImapClientConfig("imap.example.com", ImapClientConfig.Security.TLS)
        .setEventExecutor(SwingUtilities::invokeLater);   // listeners run on the Swing thread
ImapClient client = new ImapClient(config);
client.connect();
client.login("tony", password);

for (MailboxInfo m : client.listAll()) { ... }           // name, delimiter, \Sent, \Trash...
SelectedMailbox inbox = client.select("INBOX");          // count, UIDVALIDITY, UIDs
List<MessageSummary> page = client.fetchSummaries("1:*"); // envelope, flags, size, MIME structure

MessageSummary m = page.get(0);
BodyPart text = m.getStructure().findText("PLAIN");
String body = client.fetchText(m.getUid(), text);
for (BodyPart p : m.getStructure().flatten()) {
    if (p.isAttachment()) {
        try (OutputStream out = new FileOutputStream(p.getFilename())) {
            client.fetchPart(m.getUid(), p, out, (done, total) -> progressBar.setValue(...));
        }
    }
}

client.addListener(new ImapListener() {
    public void exists(String mailbox, long count) { /* new mail: fetch it */ }
    public void expunged(String mailbox, long msn, long uid) { /* remove the row */ }
    public void flagsChanged(String mailbox, long msn, long uid, Set<String> flags) { ... }
    public void disconnected(Exception cause) { /* offer to reconnect */ }
});
client.startIdle();                                       // live updates

client.submit(c -> c.search(SearchCriteria.and(SearchCriteria.unseen(), SearchCriteria.from("fred"))))
      .thenAccept(uids -> SwingUtilities.invokeLater(() -> show(uids)));
```

Design points:

- **Thread safe.** Any thread may call any method; commands run one at a time. `submit()` runs work on the client's own thread and returns a `CompletableFuture`, so the user interface never waits on the network.
- **Events, not polling.** `ImapListener` hears about new mail, expunges, flag changes, alerts and a lost connection, from IDLE or from any command's responses. Events go to an executor you choose (`SwingUtilities::invokeLater`, `Platform::runLater`); by default a daemon thread.
- **IDLE stays out of the way.** While IDLE is running, any call stops it (DONE), runs, and starts it again. IDLE is renewed every 25 minutes. Servers without IDLE are polled with NOOP.
- **UIDs throughout.** Messages are addressed by UID. `SelectedMailbox` keeps the sequence-number-to-UID map current through EXISTS and EXPUNGE, so an event can name the message.
- **Any message size.** Bodies and attachments are streamed to an `OutputStream` with `ProgressListener` callbacks. `fetchPart` returns decoded content: the server decodes with BINARY (RFC 3516) when offered, else the client removes base64 or quoted-printable itself. `append` streams from an `InputStream`.
- **Parsed for display.** `Envelope` (with RFC 2047 names and subjects decoded) and `BodyPart` (part numbers, file names including RFC 2231, attachment detection) come from one FETCH, so a message list needs no message bodies.
- **Framework connections.** Sockets, TLS, STARTTLS and certificate trust come from the framework's `Client`. A `DynamicTrustManager.CertificateValidator` decides about certificates the system doesn't trust; the framework's `VisualCertificateValidator` is a ready-made Swing dialog, and "always" answers are remembered.
- **Protocol.** IMAP4rev2 and UTF8=ACCEPT are enabled when offered; mailbox names are UTF-8 or modified UTF-7 as the session needs. Login uses AUTHENTICATE PLAIN with SASL-IR, or LOGIN. Literals are non-synchronizing with LITERAL+ or LITERAL-. MOVE falls back to COPY + UID EXPUNGE; COPYUID and APPENDUID are returned. `execute()` sends any other command.
- **Errors.** A NO or BAD answer throws `ImapException` (status, response code such as `TRYCREATE`, server text); the connection stays usable. A lost connection throws `IOException` and fires `disconnected`.

### The command-line program

```
java -cp target/classes:<dependencies> us.bringardner.parley.imap.client.ImapCli --host imap.example.com --user tony
Connected to imap.example.com:993
Password for tony:
imap> select INBOX
INBOX: 42 messages
INBOX> ls 3
    40     2026-10-01 09:12  Fred Foo              Lunch?                                    2.1 KB
    41 N   2026-10-02 15:00  José                  Grüße                                     1.4 KB
    42 NF @ 2026-10-03 08:30  Build server          Nightly report                           88.0 KB
3 of 42 messages
INBOX> show 42
INBOX> get 42 2 report.pdf
```

Options: `--host`, `--port`, `--tls` (the default, port 993), `--starttls`, `--plain` (STARTTLS if offered), `--insecure`, `--user`, `--password` (else `$IMAP_PASSWORD`, else a prompt), `--trust-all` (test servers only), `--timeout SECONDS`, `--rev1`, `--trace` (the protocol, with passwords hidden).

Commands (type `help`): `list`, `lsub`, `status`, `select`, `examine`, `create`, `delete`, `rename`, `subscribe`, `unsubscribe`, `close`, `ls`, `show`, `parts`, `save`, `get`, `search`, `flag`, `unflag`, `read`, `unread`, `rm`, `expunge`, `cp`, `mv`, `put`, `idle`, `caps`, `noop`, `raw`, `trace`, `quit`.

Commands can also come from a script on standard input, or one command can follow the options (`ImapCli --host h --user u status INBOX`). The exit code is 0 if every command worked, 1 if one failed, 2 for a usage error. An untrusted certificate is shown with its SHA-256 fingerprint and you're asked whether to trust it; without a terminal it is rejected.

### Testing

`TestImapClient` runs the client and the program against `ImapServer`: IMAP4rev2 and IMAP4rev1 (modified UTF-7), STARTTLS and implicit TLS, MIME parts and decoding, search, flags, copy, move, IDLE events between two sessions, calls from several threads, a script for `ImapCli`, and a 70 MB message appended and fetched (whole and as a decoded attachment) under the 64 MB test heap. `TestImapClientParts` tests the response parser and the envelope and body structure parsing without a server.
