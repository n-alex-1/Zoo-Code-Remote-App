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
		if (t.isEmpty()) return fail("Token fehlt - siehe VS-Code-Einstellungen \"Remote Control\".")
		if (fp.replace(":", "").isEmpty()) return fail("Zertifikats-Fingerprint fehlt (aus dem OutputChannel \"Zoo Remote\").")

		_phase.value = SetupPhase.Testing
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val client = TlsTrust.client(fp, connectTimeoutMs = 10_000L)
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
				fail("Zertifikat geändert - neu pairen? Erwartet wurde ${TlsTrust.normalizeFingerprint(fp)}, der Server zeigt ${TlsTrust.normalizeFingerprint(e.actual)}. Fingerprint aus dem OutputChannel \"Zoo Remote\" erneut ablesen.")
			} catch (e: ZooApiException) {
				fail(when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). Token in VS-Code unter Einstellungen > Remote Control prüfen."
					else -> "Server antwortete mit HTTP ${e.httpCode} - Details: ${e.body.orEmpty().take(200)}"
				})
			} catch (_: UnknownHostException) {
				fail("Host unerreichbar ($h). IP-Adresse und Portfreigabe im Router prüfen. Emulator: 10.0.2.2.")
			} catch (e: SocketTimeoutException) {
				fail("Zeitüberschreitung nach 10 s - Host $h:$port antwortet nicht. Firewall/Portfreigabe prüfen.")
			} catch (_: ConnectException) {
				fail("Verbindung abgelehnt von $h:$port - läuft der Remote-Server (Einstellungen > Remote Control aktiv)?")
			} catch (e: Exception) {
				val detail = e.message ?: e::class.java.simpleName
				fail("Unerwarteter Fehler: $detail")
			}
		}
	}

	private fun fail(message: String) {
		_phase.value = SetupPhase.Failure(message)
	}

	private fun formatPercent(p: Double): String = "%.2f".format(p).replace('.', ',')
}
