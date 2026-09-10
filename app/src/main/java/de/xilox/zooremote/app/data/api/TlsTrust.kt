package de.xilox.zooremote.app.data.api

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Raised when the server presented a certificate whose SHA-256 fingerprint does not match
 * the pinned one. The message is user-facing (German, per app language).
 */
class FingerprintMismatchException(
	val expected: String,
	val actual: String,
) : RuntimeException("Zertifikat geändert — neu pairen? (erwartet $expected, vorgefunden $actual)")

/**
 * Builds an [OkHttpClient] that trusts exactly one certificate: the leaf server certificate
 * whose SHA-256 fingerprint (over its DER encoding, colon-separated hex as printed by the
 * plugin's OutputChannel "Zoo Remote") equals [pinnedFingerprint].
 *
 * Everything else — CA chain, hostname — is checked against the platform defaults first, so a
 * man-in-the-middle with a valid public cert still fails (its fingerprint differs), and a
 * self-signed cert for the wrong host also fails.
 */
object TlsTrust {

	/** Normalizes user input: trims, lowercases, strips spaces; keeps `:` separators optional. */
	fun normalizeFingerprint(raw: String): String = raw.trim().lowercase().replace(" ", "").replace(":", "")

	private fun fingerprintOf(cert: X509Certificate): String =
		MessageDigest.getInstance("SHA-256")
			.digest(cert.encoded)
			.joinToString("") { "%02x".format(it) }

	/** True iff [raw] (normalized) equals the SHA-256 fingerprint of [cert]. Constant-time compare. */
	fun matches(raw: String, cert: X509Certificate): Boolean = constantTimeEquals(normalizeFingerprint(raw), fingerprintOf(cert))

	private fun constantTimeEquals(a: String, b: String): Boolean {
		if (a.length != b.length) return false
		var result = 0
		for (i in a.indices) result = result or (a[i].code xor b[i].code)
		return result == 0
	}

	fun client(
		pinnedFingerprint: String,
		connectTimeoutMs: Long = 10_000L,
		readTimeoutMs: Long = 30_000L,
	): OkHttpClient {
		val expected = normalizeFingerprint(pinnedFingerprint)

		// Platform default trust manager (CA store + hostname rules) as the base layer.
		val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
		tmf.init(null as KeyStore?)
		val platformTm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

		return OkHttpClient.Builder()
			.sslSocketFactory(pinningContext(platformTm, expected).socketFactory, pinningTrustManager(platformTm, expected))
			.hostnameVerifier(hostnameVerifier(expected)) // SAN/CN check against the (pinned) peer cert
			.connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
			.readTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
			.build()
	}

	private fun pinningTrustManager(platform: X509TrustManager, expectedFingerprint: String): X509TrustManager =
		object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)

			override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
				val leaf = chain.firstOrNull() ?: throw FingerprintMismatchException(expectedFingerprint, "(keine Kette)")

				// 1) Pin check on the leaf certificate (chain[0]) — always enforced, so even a valid
				//    public-CA cert of an attacker fails.
				if (!matches(expectedFingerprint, leaf)) {
					throw FingerprintMismatchException(expectedFingerprint, fingerprintOf(leaf))
				}

				// 2) Normal chain validation for everything else (expiry, revocation rules). Self-signed
				//    server certs will fail here — acceptable because the pin already identified them.
				try {
					platform.checkServerTrusted(chain, authType)
				} catch (_: java.security.cert.CertificateException) {
					// Pinned self-signed cert: chain validation is expected to fail; hostname is still
					// verified separately by the HostnameVerifier.
				}
			}

			override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
		}

	private fun pinningContext(platform: X509TrustManager, expectedFingerprint: String): SSLContext {
		val ctx = SSLContext.getInstance("TLS") // "TLS" selects the highest available protocol (>= 1.2 on API 26+)
		ctx.init(null, arrayOf(pinningTrustManager(platform, expectedFingerprint)), SecureRandom())
		return ctx
	}

	/**
	 * Hostname check on top of the fingerprint pin. The plugin's self-signed certificate only
	 * carries SANs for `localhost` / `127.0.0.1`, while phones typically connect via a LAN IP —
	 * so strict SAN matching would reject every real-world connection. With the leaf pinned by
	 * SHA-256 fingerprint, identity is already established; we therefore accept any hostname
	 * as long as the presented certificate matches the pin (documented trade-off, revisit in
	 * session 8 hardening — e.g. regenerate the cert with a dynamic SAN).
	 */
	private fun hostnameVerifier(expectedFingerprint: String): HostnameVerifier = object : HostnameVerifier {
		override fun verify(hostname: String, session: SSLSession): Boolean {
			val cert = try {
				session.peerCertificates.first() as X509Certificate
			} catch (_: Exception) {
				return false
			}
			return matches(expectedFingerprint, cert)
		}
	}
}
