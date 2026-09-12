package de.xilox.zooremote.app.data.connection

import android.content.Context
import de.xilox.zooremote.app.data.ConnectionSettings
import de.xilox.zooremote.app.data.SettingsRepository
import de.xilox.zooremote.app.data.api.ActivityItem
import de.xilox.zooremote.app.data.api.RemoteActivityPayload
import de.xilox.zooremote.app.data.api.RemoteStatus
import de.xilox.zooremote.app.data.api.TlsTrust
import de.xilox.zooremote.app.data.api.ZooApi
import de.xilox.zooremote.app.data.ws.StatusSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Connection lifecycle as seen by the UI. */
sealed interface ConnectionState {
	data object Disconnected : ConnectionState

	/** Initial connect or a reconnect attempt (exponential backoff). */
	data object Connecting : ConnectionState

	data object Connected : ConnectionState

	/** Non-retryable error (wrong token, changed certificate) — user must re-pair in setup. */
	data class Error(val message: String) : ConnectionState
}

/**
 * Central connection holder ("hoisted state", session 5 spec): owns the [StatusSocket] so that a
 * later foreground service (session 6) can reuse it, and maintains the shared UI state —
 * connection state, latest [RemoteStatus] and the activity feed.
 */
class ConnectionRepository(context: Context) {

	companion object {
		/** In-memory cap for the activity feed; oldest entries are dropped first. */
		const val MAX_FEED_ENTRIES = 200
	}

	private val settingsRepository = SettingsRepository(context.applicationContext)

	private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
	/** Current connection state. */
	val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

	private val _status = MutableStateFlow<RemoteStatus?>(null)
	/** Latest status snapshot (WS `status` events). */
	val status: StateFlow<RemoteStatus?> = _status.asStateFlow()

	// Session 9: workspace folder of the extension host — lets the session picker request the
	// per-workspace history (`GET /api/tasks?workspace=…`) like the webview's own list does.
	private val _activeWorkspace = MutableStateFlow<String?>(null)
	/** Workspace of the connected extension host (from `status.connection.workspace`). */
	val activeWorkspace: StateFlow<String?> = _activeWorkspace.asStateFlow()

	private val _activity = MutableStateFlow<List<ActivityItem>>(emptyList())
	/** Activity feed; entries with the same [RemoteActivityPayload.ts] replace each other (streaming). */
	val activity: StateFlow<List<ActivityItem>> = _activity.asStateFlow()

	// Session 8b: last REST-level auth failure (HTTP 401 / wrong token). While non-null the UI is
	// expected to show the setup screen with this message; cleared on a successful connection test.
	private val _authError = MutableStateFlow<String?>(null)
	val authError: StateFlow<String?> = _authError.asStateFlow()

	fun clearAuthError() {
		_authError.value = null
	}

	/** Stops any socket/reconnect loop and flags the setup screen with [message] (session 8b). */
	fun reportAuthFailure(message: String) {
		disconnect()
		_authError.value = message
	}

	@Volatile
	private var socket: StatusSocket? = null

	@Volatile
	private var lastTaskId: String? = null

	/** Mutable backing list for [activity]; all mutations happen inside `synchronized(this)`. */
	private val feed = mutableListOf<RemoteActivityPayload>()

	/** True while a socket is open or being (re-)established. */
	fun isActive(): Boolean = socket?.isRunning == true

	suspend fun savedSettings(): ConnectionSettings = settingsRepository.observe().first()

	/** (Re)connect with [settings]: closes any previous socket first and resets feed/status. */
	fun connect(settings: ConnectionSettings) {
		if (!settings.isValid()) return
		disconnect()
		synchronized(this) {
			feed.clear()
			lastTaskId = null
			_activity.value = emptyList()
		}
		_status.value = null
		_activeWorkspace.value = null
		_connectionState.value = ConnectionState.Connecting

		val client = TlsTrust.client(settings.certFingerprint)
		val url = "wss://${settings.host.trim()}:${settings.port}/events"
		socket = StatusSocket(client, url, settings.token).also { it.start(listener) }
	}

	/** Closes the socket and stops reconnect attempts. */
	fun disconnect() {
		socket?.stop()
		socket = null
		_connectionState.value = ConnectionState.Disconnected
	}

	/**
	 * Pull-to-refresh fallback (session 7): fetches `GET /api/status` with the saved settings and
	 * adopts it as the current snapshot. Returns true when a fresh status was applied. Runs on
	 * [Dispatchers.IO] by its callers; throws for connection/HTTP errors (see ZooApi).
	 */
	suspend fun refreshStatus(): Boolean {
		val settings = savedSettings()
		if (!settings.isValid()) return false
		val api = ZooApi(TlsTrust.client(settings.certFingerprint), settings.baseUrl(), settings.token)
		val status = api.status()
		applySnapshot(status, clearFeedOnNewTask = true)
		return true
	}

	/** Adopts a fresh status from an action response (session 7); same semantics as WS `status` events. */
	fun adoptStatus(status: RemoteStatus) = applySnapshot(status, clearFeedOnNewTask = true)

	private fun applySnapshot(status: RemoteStatus, clearFeedOnNewTask: Boolean) {
		val taskId = status.task.taskId
		if (status.connection.workspace != _activeWorkspace.value) {
			_activeWorkspace.value = status.connection.workspace
		}
		synchronized(this@ConnectionRepository) {
			if (clearFeedOnNewTask && taskId != null && taskId != lastTaskId) {
				// New task started in the plugin → clear the feed; the server's activity
				// snapshot only ever covers the active task.
				feed.clear()
				_activity.value = emptyList()
			}
			lastTaskId = taskId
		}
		_status.value = status
		if (_connectionState.value == ConnectionState.Disconnected) {
			_connectionState.value = ConnectionState.Connected
		}
	}

	private val listener = object : StatusSocket.Listener {
		override fun onConnected() {
			// The server is about to send fresh status + activity snapshots — adopt them as the
			// initial state, so clear whatever we had before (covers reconnects).
			synchronized(this@ConnectionRepository) {
				feed.clear()
				lastTaskId = null
				_activity.value = emptyList()
			}
			_connectionState.value = ConnectionState.Connected
		}

		override fun onStatus(status: RemoteStatus) {
			applySnapshot(status, clearFeedOnNewTask = true)
		}

		override fun onActivity(payload: RemoteActivityPayload) {
			synchronized(this@ConnectionRepository) {
				val index = feed.indexOfLast { it.ts == payload.ts }
				if (index >= 0) feed[index] = payload else feed.add(payload)
				while (feed.size > MAX_FEED_ENTRIES) feed.removeAt(0)
				_activity.value = feed.toList()
			}
		}

		override fun onActivitySnapshot(payloads: List<RemoteActivityPayload>) {
			// Session 9: the plugin switched to a different task — replace the whole feed with
			// the new task's history instead of waiting for live events that only cover the future.
			synchronized(this@ConnectionRepository) {
				feed.clear()
				feed.addAll(payloads.takeLast(MAX_FEED_ENTRIES))
				lastTaskId = null // next status re-arms the per-task feed clearing
				_activity.value = feed.toList()
			}
		}

		override fun onDisconnected(code: Int, reason: String) {
			_connectionState.value = ConnectionState.Connecting
		}

		override fun onReconnecting(attempt: Int) {
			_connectionState.value = ConnectionState.Connecting
		}

		override fun onTerminalError(message: String) {
			_connectionState.value = ConnectionState.Error(message)
		}
	}
}
