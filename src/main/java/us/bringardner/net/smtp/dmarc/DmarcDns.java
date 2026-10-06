package us.bringardner.net.smtp.dmarc;

import us.bringardner.net.dns.resolve.LookupResult;

/** Finds the TXT records of a name (DMARC policy records at _dmarc.domain); BjlDns in the server. */
@FunctionalInterface
public interface DmarcDns {

	LookupResult<String> txt(String name);
}
