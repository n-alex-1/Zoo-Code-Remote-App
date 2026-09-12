package de.xilox.zooremote.app.ui.status

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.api.ActivityItem
import de.xilox.zooremote.app.data.api.RemoteSuggestion
import de.xilox.zooremote.app.data.api.RemoteStatus
import de.xilox.zooremote.app.data.api.TlsTrust
import de.xilox.zooremote.app.data.api.ZooApi
import de.xilox.zooremote.app.data.api.ZooApiException
import de.xilox.zooremote.app.data.connection.ConnectionRepository
import de.xilox.zooremote.app.data.connection.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the status screen (sessions 5+6). */
data class StatusUiState(
	val connectionState: ConnectionState = ConnectionState.Disconnected,
	/** Latest `GET /api/status` / WS `status` snapshot. */
	val lastStatus: RemoteStatus? = null,
	/** Chat-style activity feed; entries with the same ts replace each other (streaming). */
	val activity: List<ActivityItem> = emptyList(),
	/** True while an action (approve/deny/answer/suggestion) is in flight. */
	val busy: Boolean = false,
	/** User-facing German error of the last failed action (auto-clears after a few seconds). */
	val actionError: String? = null,
)

class StatusViewModel(application: Application) : AndroidViewModel(application) {

	private val repository: ConnectionRepository = ZooRemoteApp.from(application).connectionRepository

	private val busyFlow = MutableStateFlow(false)
	private val errorFlow = MutableStateFlow<String?>(null)
	private var errorClearJob: Job? = null

	/** Combined, hoisted state — the single source of truth for [StatusScreen]. */
	val uiState: StateFlow<StatusUiState> = combine(
		repository.connectionState,
		repository.status,
		repository.activity,
		busyFlow,
		errorFlow,
	) { connection, status, activity, busy, error ->
		StatusUiState(connectionState = connection, lastStatus = status, activity = activity, busy = busy, actionError = error)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState())

	init {
		// Auto-connect on start when valid settings were saved (e.g. after the setup flow).
		viewModelScope.launch {
			val settings = repository.savedSettings()
			if (settings.isValid()) repository.connect(settings)
		}
	}

	/** Approve the pending ask (`yesButtonClicked`, same path as the webview). */
	fun approve() = runAction { it.respondToAsk("yesButtonClicked") }

	/** Deny the pending ask (`noButtonClicked`). */
	fun deny() = runAction { it.respondToAsk("noButtonClicked") }

	/** Free-text answer (`messageResponse` + [text]). */
	fun sendText(text: String) {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return
		runAction { api -> api.respondToAsk("messageResponse", trimmed) }
	}

	/**
	 * Taps a follow-up suggestion: sends it as `messageResponse`; if the suggestion carries a mode
	 * different from the current one, immediately follows with `POST /api/mode` (session 6).
	 */
	fun tapSuggestion(suggestion: RemoteSuggestion) {
		val targetMode = suggestion.mode
		runAction { api ->
			api.respondToAsk("messageResponse", suggestion.answer)
			if (!targetMode.isNullOrBlank() && targetMode != repository.status.value?.mode?.current) {
				api.setMode(targetMode)
			}
		}
	}

	/**
	 * Pull-to-refresh fallback (session 7): fetches `GET /api/status` when the WebSocket feed is
	 * stale or absent. Shares [busyFlow] with the other actions so controls stay disabled while it
	 * runs; failures surface via [StatusUiState.actionError].
	 */
	fun pullToRefresh() {
		viewModelScope.launch(Dispatchers.IO) {
			if (busyFlow.value) return@launch
			busyFlow.value = true
			errorClearJob?.cancel()
			errorFlow.value = null
			try {
				repository.refreshStatus()
			} catch (e: ZooApiException) {
				val message = when (e.httpCode) {
					401 -> "Falscher Token (HTTP 401). Einstellungen prüfen und neu pairen."
					else -> e.message ?: "Aktualisierung fehlgeschlagen"
				}
				failAction(message)
				if (e.httpCode == 401) repository.reportAuthFailure(message) // session 8b: back to setup
			} catch (e: Exception) {
				failAction(e.message ?: e::class.java.simpleName)
			} finally {
				busyFlow.value = false
			}
		}
	}

	private fun runAction(call: (ZooApi) -> Unit) {
		viewModelScope.launch(Dispatchers.IO) {
			if (busyFlow.value) return@launch
			busyFlow.value = true
			errorClearJob?.cancel()
			errorFlow.value = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException("Keine gültigen Verbindungseinstellungen.")
				call(ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token))
			} catch (e: ZooApiException) {
				val message = when (e.httpCode) {
					409 -> "Anfrage bereits beantwortet — Status wird neu geladen."
					401 -> "Falscher Token (HTTP 401). Einstellungen prüfen und neu pairen."
					else -> e.message ?: "Aktion fehlgeschlagen"
				}
				failAction(message)
				if (e.httpCode == 401) repository.reportAuthFailure(message) // session 8b: back to setup
			} catch (e: Exception) {
				failAction(e.message ?: e::class.java.simpleName)
			} finally {
				busyFlow.value = false
			}
		}
	}

	private fun failAction(message: String) {
		errorFlow.value = message
		errorClearJob?.cancel()
		errorClearJob = viewModelScope.launch {
			delay(6_000L)
			if (errorFlow.value == message) errorFlow.value = null
		}
	}
}
