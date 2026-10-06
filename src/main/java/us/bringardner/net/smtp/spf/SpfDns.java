package us.bringardner.net.smtp.spf;

import us.bringardner.net.dns.RR;
import us.bringardner.net.dns.resolve.LookupResult;

/**
 * The DNS queries an SPF check makes (TXT, A, AAAA, MX, PTR). The SMTP
 * server uses BjlDns ({@code BjlDnsMxResolver::records}); tests use a map.
 */
@FunctionalInterface
public interface SpfDns {

	/** The records of one type (DNS.TXT, DNS.A...) for a name, class IN. */
	LookupResult<RR> lookup(String name, int type);
}
