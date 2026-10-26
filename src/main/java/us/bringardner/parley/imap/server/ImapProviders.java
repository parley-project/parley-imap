package us.bringardner.parley.imap.server;

import java.util.Properties;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The IMAP servers a launcher can run ({@code parley.servers=imap,imaps}): each is made from its
 * own configuration, read from {@code parley-mail.properties}, then {@code parley-imap.properties}
 * (or the file {@code parley.mail.imap.configFile} names).
 *
 * @since 2.0.0
 */
public final class ImapProviders {

	private ImapProviders() {
	}

	/** {@code parley.mail.imap.secure=true}: the older way to run IMAP with implicit TLS only */
	static boolean legacySecure(Properties configuration) {
		String v = configuration.getProperty(ImapServer.IMAP_PREFIX + ".secure");
		return v != null && Boolean.parseBoolean(v.trim());
	}

	/** IMAP (port 143, STARTTLS): {@code imap}, on unless parley.mail.imap.port is 0 */
	public static final class Imap extends MailServerProvider {
		public Imap() {
			super("imap", "IMAP", "parley-imap.properties", ImapServer.CONFIG_PROP);
		}

		@Override
		public int port(Properties configuration) {
			return legacySecure(configuration) ? 0 : intProperty(configuration, ImapServer.IMAP_PREFIX + ".port", IMAP.IMAP_PORT);
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
			int port = port(configuration);
			return ServerMain.service(AbstractMailServer.makeIn(context, configuration, () -> new ImapServer(port, ImapServer.IMAP_NAME, false)),
					"IMAP server");
		}
	}

	/** IMAP with implicit TLS (port 993): {@code imaps}, on when parley.mail.imaps.port is set (and not 0) */
	public static final class Imaps extends MailServerProvider {
		public Imaps() {
			super("imaps", "IMAP with implicit TLS", "parley-imap.properties", ImapServer.CONFIG_PROP);
		}

		@Override
		public int port(Properties configuration) {
			return intProperty(configuration, ImapServer.IMAPS_PREFIX + ".port", legacySecure(configuration) ? IMAP.IMAPS_PORT : 0);
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
			int port = port(configuration);
			return ServerMain.service(AbstractMailServer.makeIn(context, configuration, () -> new ImapServer(port, ImapServer.IMAP_NAME, true)),
					"IMAP server (implicit TLS)");
		}
	}
}
