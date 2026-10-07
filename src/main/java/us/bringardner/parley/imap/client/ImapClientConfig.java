package us.bringardner.parley.imap.client;

import java.util.concurrent.Executor;

import us.bringardner.parley.net.client.DynamicTrustManager;

/**
 * How an {@link ImapClient} connects. Certificates are checked with the BJL
 * framework's {@link DynamicTrustManager}: the JVM trust store, then certificates
 * the user accepted before, then the {@link #setCertificateValidator certificate
 * validator} (a desktop application can ask the user, e.g. with the framework's
 * VisualCertificateValidator).
 */
public class ImapClientConfig {

	/** How the connection is secured. */
	public enum Security {
		/** TLS from the start (port 993, RFC 8314). Recommended. */
		TLS,
		/** Plain connection upgraded with STARTTLS; fails if the server doesn't offer it. */
		STARTTLS,
		/** STARTTLS when offered, plain otherwise (only for trusted networks). */
		STARTTLS_IF_AVAILABLE,
		/** No encryption (only for testing or local servers). */
		NONE
	}

	private String host = "localhost";
	private int port = -1;
	private Security security = Security.TLS;
	private int connectTimeout = 30_000;
	private int readTimeout = 5 * 60_000;
	private boolean trustAllCertificates;
	private DynamicTrustManager.CertificateValidator certificateValidator;
	private Executor eventExecutor;
	private boolean enableRev2 = true;
	private boolean enableUtf8 = true;
	private int idleRestartMinutes = 25;
	private int pollSeconds = 60;
	private long maxMemoryLiteral = 64L * 1024 * 1024;

	public ImapClientConfig() {
	}

	public ImapClientConfig(String host, Security security) {
		this.host = host;
		this.security = security;
	}

	public String getHost() {
		return host;
	}

	public ImapClientConfig setHost(String host) {
		this.host = host;
		return this;
	}

	/** The port; by default 993 for {@link Security#TLS}, else 143. */
	public int getPort() {
		return port > 0 ? port : security == Security.TLS ? 993 : 143;
	}

	public ImapClientConfig setPort(int port) {
		this.port = port;
		return this;
	}

	public Security getSecurity() {
		return security;
	}

	public ImapClientConfig setSecurity(Security security) {
		this.security = security;
		return this;
	}

	public int getConnectTimeout() {
		return connectTimeout;
	}

	public ImapClientConfig setConnectTimeout(int connectTimeout) {
		this.connectTimeout = connectTimeout;
		return this;
	}

	/** How long to wait for the server during a command (ms). */
	public int getReadTimeout() {
		return readTimeout;
	}

	public ImapClientConfig setReadTimeout(int readTimeout) {
		this.readTimeout = readTimeout;
		return this;
	}

	public boolean isTrustAllCertificates() {
		return trustAllCertificates;
	}

	/** Accept any certificate. Only for testing. */
	public ImapClientConfig setTrustAllCertificates(boolean trustAllCertificates) {
		this.trustAllCertificates = trustAllCertificates;
		return this;
	}

	public DynamicTrustManager.CertificateValidator getCertificateValidator() {
		return certificateValidator;
	}

	/** Asked about certificates the JVM doesn't trust (e.g. a dialog in a desktop application). */
	public ImapClientConfig setCertificateValidator(DynamicTrustManager.CertificateValidator certificateValidator) {
		this.certificateValidator = certificateValidator;
		return this;
	}

	public Executor getEventExecutor() {
		return eventExecutor;
	}

	/**
	 * Where {@link ImapListener} events run. For Swing use
	 * {@code SwingUtilities::invokeLater}, for JavaFX {@code Platform::runLater}.
	 * By default a single background thread.
	 */
	public ImapClientConfig setEventExecutor(Executor eventExecutor) {
		this.eventExecutor = eventExecutor;
		return this;
	}

	public boolean isEnableRev2() {
		return enableRev2;
	}

	/** Use IMAP4rev2 (RFC 9051) when the server offers it. Default true. */
	public ImapClientConfig setEnableRev2(boolean enableRev2) {
		this.enableRev2 = enableRev2;
		return this;
	}

	public boolean isEnableUtf8() {
		return enableUtf8;
	}

	/** ENABLE UTF8=ACCEPT (RFC 6855) when offered. Default true. */
	public ImapClientConfig setEnableUtf8(boolean enableUtf8) {
		this.enableUtf8 = enableUtf8;
		return this;
	}

	public int getIdleRestartMinutes() {
		return idleRestartMinutes;
	}

	/** IDLE is restarted this often (RFC 9051 recommends less than 30 minutes). */
	public ImapClientConfig setIdleRestartMinutes(int idleRestartMinutes) {
		this.idleRestartMinutes = idleRestartMinutes;
		return this;
	}

	public int getPollSeconds() {
		return pollSeconds;
	}

	/** For servers without IDLE: how often {@link ImapClient#startIdle()} polls with NOOP. */
	public ImapClientConfig setPollSeconds(int pollSeconds) {
		this.pollSeconds = pollSeconds;
		return this;
	}

	public long getMaxMemoryLiteral() {
		return maxMemoryLiteral;
	}

	/** The largest string kept in memory (message bodies are always streamed). */
	public ImapClientConfig setMaxMemoryLiteral(long maxMemoryLiteral) {
		this.maxMemoryLiteral = maxMemoryLiteral;
		return this;
	}
}
