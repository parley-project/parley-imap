package us.bringardner.parley.imap.server;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The IMAP servers a launcher can run ({@code parley.servers=imap,imaps}): each is made from its
 * own configuration, read from {@code parley-mail.properties}, then {@code parley-imap.properties}
 * (or the file {@code parley.mail.imap.configFile} names). Each runs when parley.servers lists it, on
 * its own port (parley.mail.imap.port, 143; parley.mail.imaps.port, 993); imaps is skipped with a
 * warning while it has no TLS key store.
 *
 * @since 2.0.0
 */
public final class ImapProviders {

	private ImapProviders() {
	}

	/** Read by 1.x to run IMAP with implicit TLS only; 2.0.0 runs imaps when parley.servers lists it */
	static final Map<String, String> OBSOLETE = Collections.singletonMap(ImapServer.IMAP_PREFIX + ".secure",
			"for IMAP with implicit TLS list imaps in parley.servers (and leave imap out to run it alone)");

	/** IMAP (port 143, STARTTLS): {@code imap} */
	public static final class Imap extends MailServerProvider {
		public Imap() {
			super("imap", "IMAP", "parley-imap.properties", ImapServer.CONFIG_PROP, ImapServer.IMAP_PREFIX, IMAP.IMAP_PORT, false);
		}

		@Override
		protected Map<String, String> obsoleteSettings() {
			return OBSOLETE;
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
			int port = port(configuration);
			return ServerMain.service(AbstractMailServer.makeIn(context, configuration, () -> new ImapServer(port, ImapServer.IMAP_NAME, false)),
					"IMAP server");
		}
	}

	/** IMAP with implicit TLS (port 993): {@code imaps}; it needs a TLS key store */
	public static final class Imaps extends MailServerProvider {
		public Imaps() {
			super("imaps", "IMAP with implicit TLS", "parley-imap.properties", ImapServer.CONFIG_PROP, ImapServer.IMAPS_PREFIX, IMAP.IMAPS_PORT,
					true);
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
			int port = port(configuration);
			return ServerMain.service(AbstractMailServer.makeIn(context, configuration, () -> new ImapServer(port, ImapServer.IMAP_NAME, true)),
					"IMAP server (implicit TLS)");
		}
	}
}
