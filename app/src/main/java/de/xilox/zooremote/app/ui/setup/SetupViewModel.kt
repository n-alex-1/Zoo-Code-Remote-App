package de.xilox.zooremote.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.xilox.zooremote.app.data.ConnectionSettings
import de.xilox.zooremote.app.data.SettingsRepository
import de.xilox.zooremote.app.data.api.FingerprintMismatchException
import de.xilox.zooremote.app.data.api.TlsTrust
import de.xilox.zooremote.app.data.api.ZooApi
import de.xilox.zooremote.app.data.api.ZooApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** UI state of the setup/connection screen. */
sealed interface SetupPhase {
	data object Idle : SetupPhase
	data object Testing : SetupPhase
	/** Connected: [statusLine] shows mode/model from `GET /api/status`. Settings are saved. */
	data class Success(val statusLine: String) : SetupPhase
	/** Not connected: [message] is a user-facing German error description. */
	data class Failure(val message: String) : SetupPhase
}

class SetupViewModel(application: Application) : AndroidViewModel(application) {

	private val repository = SettingsRepository(application)

	val host = MutableStateFlow("")
	val portText = MutableStateFlow("8999")
	val token = MutableStateFlow("")
	val fingerprint = MutableStateFlow("")

	/** Fingerprint of the certificate presented during pairing (TOFU), for visual comparison with VS Code. */
	val pairedFingerprint = MutableStateFlow<String?>(null)

	private val _phase = MutableStateFlow<SetupPhase>(SetupPhase.Idle)
	/** Exposed state for the UI (collectAsState in the screen). */
	val phase: StateFlow<SetupPhase> = _phase.asStateFlow()

	init {
		viewModelScope.launch { repository.observe().collect { s ->
			if (s.host.isNotEmpty()) host.value = s.host
			portText.value = s.port.toString()
			token.value = s.token
			fingerprint.value = s.certFingerprint
		} }
	}

	fun testConnection() {
		val h = host.value.trim()
		val port = portText.value.toIntOrNull()
		val t = token.value.trim()
		val fp = fingerprint.value.trim()

		if (h.isEmpty()) return fail("Host/IP fehlt.")
		if (port == null || port !in 1024..65535) return fail("Port muss eine Zahl zwischen 1024 und 65535 sein.")
		if (t.isEmpty()) return fail("Token fehlt - siehe VS-Code-Einstellungen \"Remote Control\" oder Button \"Pairing\".")
		if (fp.replace(":", "").isEmpty()) return fail("Zertifikats-Fingerprint fehlt (wird beim Pairing automatisch uebernommen).")

		connectAndSave(h, port, t, fp)
	}

	/**
	 * Session 9b: one-shot pairing. Only host/IP + port are needed; the token and certificate
	 * fingerprint are fetched from `POST /api/pair` (unauthenticated, TOFU TLS) while the plugin's
	 * pairing window is open — no manual copying anymore. After a successful fetch the usual
	 * pinned verification (`GET /api/status`) runs before anything is saved.
	 */
	fun startPairing() {
		val h = host.value.trim()
		val port = portText.value.toIntOrNull()

		if (h.isEmpty()) return fail("Host/IP fehlt.")
		if (port == null || port !in 1024..65535) return fail("Port muss eine Zahl zwischen 1024 und 65535 sein.")

		_phase.value = SetupPhase.Testing
		viewModelScope.launch(Dispatchers.IO) {
			try {
				// Session 9d: short timeouts (5 s connect / 10 s read) so a firewall that swallows the
				// request fails fast instead of leaving the user staring at a spinner for minutes.
				val client = TlsTrust.pairingClient(
					onPeerCertificate = { cert ->
						pairedFingerprint.value = TlsTrust.formatForDisplay(cert)
					},
					connectTimeoutMs = 5_000L,
					readTimeoutMs = 10_000L,
				)
				val api = ZooApi(client, "https://$h:$port", token = "")
				val pairResult = api.pair()

				val t = pairResult.token.orEmpty().trim()
				val fp = pairResult.fingerprint.orEmpty().trim()
				if (t.isEmpty() || fp.replace(":", "").isEmpty()) {
					return@launch fail("Pairing-Antwort unvollstaendig (Token oder Fingerprint fehlt).")
				}

				// Fill the fields for transparency, then verify with pinning before saving.
				token.value = t
				fingerprint.value = fp
				connectAndSave(h, port, t, fp)
			} catch (e: ZooApiException) {
				fail(when {
					e.httpCode == 409 && e.body.orEmpty().contains("already_paired") ->
						"Bereits gepaart - in VS-Code unter Einstellungen > Remote Control auf \"Zuruecksetzen\" tippen, dann hier erneut \"Pairing\"."
					e.httpCode == 409 && e.body.orEmpty().contains("no_pairing_window") ->
						"Kein Pairing-Fenster offen (laeuft 120 s). In VS-Code unter Einstellungen > Remote Control auf \"Pairing starten\" tippen, dann hier erneut \"Pairing\"."
					e.httpCode == 409 -> "Server: Pairing nicht moeglich (${e.body.orEmpty().take(120)})."
					else -> "Server antwortete mit HTTP ${e.httpCode} - Details: ${e.body.orEmpty().take(200)}"
				})
			} catch (_: UnknownHostException) {
				fail("Host unerreichbar ($h). IP-Adresse und Portfreigabe im Router pruefen. Emulator: 10.0.2.2.")
			} catch (_: SocketTimeoutException) {
				fail(timeoutMessage(h, port))
			} catch (_: ConnectException) {
				fail("Verbindung abgelehnt von $h:$port - laeuft der Remote-Server (Einstellungen > Remote Control aktiv)?")
			} catch (e: Exception) {
				val detail = e.message ?: e::class.java.simpleName
				fail("Unerwarteter Fehler: $detail")
			}
		}
	}

	/** Pinned verification (`GET /api/status` with the fetched/typed credentials), then saves. */
	private fun connectAndSave(h: String, port: Int, t: String, fp: String) {
		_phase.value = SetupPhase.Testing
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val client = TlsTrust.client(fp, connectTimeoutMs = 5_000L, readTimeoutMs = 10_000L)
				val api = ZooApi(client, "https://$h:$port", t)

				api.health() // unauthenticated liveness check first
				val status = api.status()

				repository.save(ConnectionSettings(host = h, port = port, token = t, certFingerprint = fp))

				val line = buildString {
					append("Verbunden. Modus: ${status.mode.label.ifEmpty { status.mode.current }} · Modell: ${status.model.describe()}")
					status.task.contextWindow?.let { cw ->
						if (cw.percent != null) append(" · Context ${formatPercent(cw.percent)}% (${cw.used}/${cw.limit ?: "?"})")
						else if ((cw.limit ?: 0L) > 0) append(" · Context ${cw.used}/${cw.limit} Tokens")
					}
				}
				_phase.value = SetupPhase.Success(line)
			} catch (e: FingerprintMismatchException) {
				fail("Zertifikat geaendert - neu pairen? Erwartet wurde ${TlsTrust.normalizeFingerprint(fp)}, der Server zeigt ${TlsTrust.normalizeFingerprint(e.actual)}. In VS-Code \"Pairing zuruecksetzen\" druecken.")
			} catch (e: ZooApiException) {
				fail(when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). In VS-Code Einstellungen > Remote Control pruefen oder neu pairen."
					else -> "Server antwortete mit HTTP ${e.httpCode} - Details: ${e.body.orEmpty().take(200)}"
				})
			} catch (_: UnknownHostException) {
				fail("Host unerreichbar ($h). IP-Adresse und Portfreigabe im Router pruefen. Emulator: 10.0.2.2.")
			} catch (_: SocketTimeoutException) {
				fail(timeoutMessage(h, port))
			} catch (_: ConnectException) {
				fail("Verbindung abgelehnt von $h:$port - laeuft der Remote-Server (Einstellungen > Remote Control aktiv)?")
			} catch (e: Exception) {
				val detail = e.message ?: e::class.java.simpleName
				fail("Unerwarteter Fehler: $detail")
			}
		}
	}

	private fun fail(message: String) {
		_phase.value = SetupPhase.Failure(message)
	}

	/** Session 9d: one timeout message for both connect (5 s) and read (10 s) timeouts. */
	private fun timeoutMessage(h: String, port: Int): String =
		"Keine Antwort von $h:$port (Timeout nach max. 10 s). Firewall/Portfreigabe pruefen."

	private fun formatPercent(p: Double): String = "%.2f".format(p).replace('.', ',')
}
