package de.xilox.zooremote.app.data

/**
 * Persisted connection settings (DataStore Preferences).
 *
 * @property host Hostname or IP of the machine running Zoo Code. Emulator: `10.0.2.2`.
 * @property port TCP port of the remote server (default 8999, range 1024..65535 per plugin).
 * @property token Bearer token shown by the plugin in Settings → Remote Control / OutputChannel "Zoo Remote".
 * @property certFingerprint SHA-256 fingerprint of the self-signed certificate as printed by the
 *   plugin (colon-separated hex, case-insensitive — normalized on save/compare).
 */
data class ConnectionSettings(
	val host: String = "",
	val port: Int = 8999,
	val token: String = "",
	val certFingerprint: String = "",
) {
	fun isValid(): Boolean =
		host.isNotBlank() &&
			port in 1024..65535 &&
			token.isNotBlank() &&
			certFingerprint.replace(":", "").replace(" ", "").isNotEmpty()

	/** `https://host:port` — base URL for the API client. */
	fun baseUrl(): String = "https://${host.trim()}:$port"
}
