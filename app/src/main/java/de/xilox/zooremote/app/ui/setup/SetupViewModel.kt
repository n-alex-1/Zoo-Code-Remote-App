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
import de.xilox.zooremote.app.R
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

	/** Locale-aware string lookup (setup errors are built on IO threads, not composables). */
	private fun tr(@androidx.annotation.StringRes resId: Int, vararg args: Any): String {
		val app = getApplication<Application>()
		return if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)
	}

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

		if (h.isEmpty()) return fail(tr(R.string.err_host_missing))
		if (port == null || port !in 1024..65535) return fail(tr(R.string.err_port_invalid))
		if (t.isEmpty()) return fail(tr(R.string.err_token_missing))
		if (fp.replace(":", "").isEmpty()) return fail(tr(R.string.err_fingerprint_missing))

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

		if (h.isEmpty()) return fail(tr(R.string.err_host_missing))
		if (port == null || port !in 1024..65535) return fail(tr(R.string.err_port_invalid))

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
					return@launch fail(tr(R.string.err_pair_incomplete))
				}

				// Fill the fields for transparency, then verify with pinning before saving.
				token.value = t
				fingerprint.value = fp
				connectAndSave(h, port, t, fp)
			} catch (e: ZooApiException) {
				fail(when {
					e.httpCode == 409 && e.body.orEmpty().contains("already_paired") -> tr(R.string.err_already_paired)
					e.httpCode == 409 && e.body.orEmpty().contains("no_pairing_window") -> tr(R.string.err_no_pairing_window)
					e.httpCode == 409 -> tr(R.string.err_pair_not_possible, e.body.orEmpty().take(120))
					else -> tr(R.string.err_http, e.httpCode ?: 0, e.body.orEmpty().take(200))
				})
			} catch (_: UnknownHostException) {
				fail(tr(R.string.err_host_unreachable, h))
			} catch (_: SocketTimeoutException) {
				fail(timeoutMessage(h, port))
			} catch (_: ConnectException) {
				fail(tr(R.string.err_connection_refused, h, port.toString()))
			} catch (e: Exception) {
				val detail = e.message ?: e::class.java.simpleName
				fail(tr(R.string.err_unexpected, detail))
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

				val contextPart = status.task.contextWindow?.let { cw ->
					if (cw.percent != null) tr(R.string.setup_context_percent, formatPercent(cw.percent), cw.used.toString(), (cw.limit ?: "?").toString())
					else if ((cw.limit ?: 0L) > 0) tr(R.string.setup_context_tokens, cw.used.toString(), cw.limit.toString())
					else ""
				}.orEmpty()
				val line = tr(
					R.string.setup_success_line,
					status.mode.label.ifEmpty { status.mode.current },
					status.model.describe(),
					contextPart,
				)
				_phase.value = SetupPhase.Success(line)
			} catch (e: FingerprintMismatchException) {
				fail(tr(R.string.err_cert_changed_full, TlsTrust.normalizeFingerprint(fp), TlsTrust.normalizeFingerprint(e.actual)))
			} catch (e: ZooApiException) {
				fail(when (e.httpCode) {
					401 -> tr(R.string.err_wrong_token)
					else -> tr(R.string.err_http, e.httpCode ?: 0, e.body.orEmpty().take(200))
				})
			} catch (_: UnknownHostException) {
				fail(tr(R.string.err_host_unreachable, h))
			} catch (_: SocketTimeoutException) {
				fail(timeoutMessage(h, port))
			} catch (_: ConnectException) {
				fail(tr(R.string.err_connection_refused, h, port.toString()))
			} catch (e: Exception) {
				val detail = e.message ?: e::class.java.simpleName
				fail(tr(R.string.err_unexpected, detail))
			}
		}
	}

	private fun fail(message: String) {
		_phase.value = SetupPhase.Failure(message)
	}

	/** Session 9d: one timeout message for both connect (5 s) and read (10 s) timeouts. */
	private fun timeoutMessage(h: String, port: Int): String = tr(R.string.err_timeout, h, port.toString())

	private fun formatPercent(p: Double): String = "%.2f".format(p).replace('.', ',')
}
