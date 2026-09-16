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
import de.xilox.zooremote.app.R
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
	/** Session 9: text to prefill into the input row (set by suggestion taps); consumed once. */
	val prefillText: String? = null,
)

class StatusViewModel(application: Application) : AndroidViewModel(application) {

	private val repository: ConnectionRepository = ZooRemoteApp.from(application).connectionRepository

	/** Locale-aware string lookup (errors are built on IO threads, not composables). */
	private fun tr(@androidx.annotation.StringRes resId: Int): String = getApplication<Application>().getString(resId)

	private val busyFlow = MutableStateFlow(false)
	private val errorFlow = MutableStateFlow<String?>(null)
	private val prefillFlow = MutableStateFlow<String?>(null)
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
	}.combine(prefillFlow) { base, prefill -> base.copy(prefillText = prefill) }
		.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState())

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

	/**
	 * Sends the input row's text (session 9). Routing per contract §3:
	 * 1. pending ask expecting text → answer that ask (`messageResponse`),
	 * 2. otherwise an active task exists (any state, incl. idle/completed/error) → continue/queue
	 *    into the same session via `messageResponse`,
	 * 3. no task at all → start a new session (`POST /api/task/start`).
	 */
	/** Target mode of the last tapped suggestion; applied after a successful [sendText] (session 9). */
	private var pendingSuggestionMode: String? = null

	fun sendText(text: String) {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return
		val modeToApply = pendingSuggestionMode
		pendingSuggestionMode = null
		runAction { api ->
			val status = repository.status.value
			val ask = status?.task?.pendingAsk
			when {
				ask != null && ask.expectsText -> api.respondToAsk("messageResponse", trimmed)
				status?.task?.taskId != null -> api.respondToAsk("messageResponse", trimmed)
				else -> {
					val fresh = api.startTask(trimmed)
					fresh?.let(repository::adoptStatus)
				}
			}
			// Suggestion with a target mode (session 6 behaviour, now applied after the user-confirmed send).
			if (modeToApply != null && modeToApply != repository.status.value?.mode?.current) {
				val fresh = api.setMode(modeToApply)
				fresh?.let(repository::adoptStatus)
			}
		}
		prefillFlow.value = null
	}

	/** Stops the current task (`POST /api/task/cancel`, same path as the webview's cancel button). */
	fun stopTask() {
		runAction { api ->
			val fresh = api.cancelTask()
			fresh?.let(repository::adoptStatus)
		}
	}

	/**
	 * Session 9: a suggestion tap no longer auto-sends — it prefills the input row so the user can
	 * adjust the text before sending. The optional target mode is applied after a successful send.
	 */
	fun tapSuggestion(suggestion: RemoteSuggestion) {
		pendingSuggestionMode = suggestion.mode?.takeIf { it.isNotBlank() }
		prefillFlow.value = suggestion.answer
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
				val message = if (e.httpCode == 401) tr(R.string.err_wrong_token) else e.message ?: tr(R.string.err_refresh_failed)
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
			var httpCode: Int? = null
			try {
				val settings = repository.savedSettings()
				if (!settings.isValid()) throw ZooApiException(tr(R.string.err_invalid_settings))
				call(ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token))
			} catch (e: ZooApiException) {
				httpCode = e.httpCode
				val message = when (e.httpCode) {
					409 -> tr(R.string.err_already_answered)
					401 -> tr(R.string.err_wrong_token)
					else -> e.message ?: tr(R.string.err_action_failed)
				}
				failAction(message)
				if (e.httpCode == 401) repository.reportAuthFailure(message) // session 8b: back to setup
			} catch (e: Exception) {
				failAction(e.message ?: e::class.java.simpleName)
			} finally {
				busyFlow.value = false
			}

			// Session 9 fix: a 409 ("already answered") is not fatal — the pending ask is gone and
			// the stale UI state (buttons/suggestions) must be replaced by a fresh status right away,
			// otherwise every follow-up action hits the same 409 again.
			if (httpCode == 409) {
				runCatching { repository.refreshStatus() }
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
