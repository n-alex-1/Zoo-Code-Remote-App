package de.xilox.zooremote.app.data.ws

import de.xilox.zooremote.app.R
import de.xilox.zooremote.app.ZooRemoteApp
import de.xilox.zooremote.app.data.api.FingerprintMismatchException
import de.xilox.zooremote.app.data.api.RemoteActivityPayload
import de.xilox.zooremote.app.data.api.RemoteStatus
import de.xilox.zooremote.app.data.api.ZooApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket client for `wss://<host>:<port>/events` (API contract v1, section 3 of
 * `docs/architektur.md`).
 *
 * Protocol:
 * - on open the first frame is `{ "auth": "<token>" }` (the server allows 5 s and closes with
 *   code 4001 otherwise);
 * - the server answers with `{ "type": "connected" }`, then a status snapshot, an activity
 *   snapshot (last ~50 entries) and finally live `status`/`message` events;
 * - keepalive is a protocol-level ping every 30 s (OkHttp answers pongs automatically); the app
 *   additionally ignores application-level `{ "type": "ping" }` frames if they ever appear.
 *
 * Reconnect: exponential backoff 1 s → max 30 s, reset after a successful authentication.
 * Terminal errors (wrong token = close codes 4002/4003, fingerprint mismatch) stop the retry
 * loop because retrying with unchanged settings cannot succeed — the user must re-pair in setup.
 */
class StatusSocket(
	private val client: OkHttpClient,
	/** e.g. `wss://192.168.1.42:8999/events`. */
	private val url: String,
	private val token: String,
) {

	interface Listener {
		/** Server accepted the auth frame — a status + activity snapshot follows immediately. */
		fun onConnected() {}

		fun onStatus(status: RemoteStatus) {}

		fun onActivity(payload: RemoteActivityPayload) {}

		/** Whole-feed replacement (session 9): the plugin switched to a different task. */
		fun onActivitySnapshot(payloads: List<RemoteActivityPayload>) {}

		/** Socket went down; a reconnect attempt is scheduled (unless terminal). */
		fun onDisconnected(code: Int, reason: String) {}

		/** A reconnect attempt with backoff [attempt] is about to start. */
		fun onReconnecting(attempt: Int) {}

		/** Non-retryable error — user must re-pair (wrong token / changed certificate). */
		fun onTerminalError(message: String) {}
	}

	companion object {
		private const val BACKOFF_BASE_MS = 1_000L
		private const val BACKOFF_MAX_MS = 30_000L
		/** Server close codes meaning "auth failed" (see RemoteServer.ts). */
		private val AUTH_CLOSE_CODES = setOf(4002, 4003)
	}

	@Serializable
	private data class AuthFrame(val auth: String)

	@Serializable
	private data class WsFrame(val type: String = "", val payload: JsonObject? = null)

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var listener: Listener? = null
	private var socket: WebSocket? = null
	private var reconnectJob: Job? = null

	@Volatile
	private var stopped = false

	@Volatile
	private var terminal = false

	/** Bumped on every (re-)open; stale callbacks of a replaced socket are dropped. */
	@Volatile
	private var generation = 0

	/** Backoff counter; reset to 0 after a successful authentication. */
	@Volatile
	private var attempt = 0

	/** True while the socket is open or being (re-)established. */
	val isRunning: Boolean get() = !stopped && !terminal

	fun start(listener: Listener) {
		this.listener = listener
		if (!stopped && !terminal) openSocket()
	}

	fun stop() {
		stopped = true
		generation++
		reconnectJob?.cancel()
		socket?.close(1000, "client disconnect")
		scope.cancel()
	}

	private fun openSocket() {
		if (stopped || terminal) return
		generation++
		val gen = generation
		val request = Request.Builder().url(url).build()
		val newSocket = client.newWebSocket(request, object : WebSocketListener() {
			override fun onOpen(webSocket: WebSocket, response: Response) {
				webSocket.send(ZooApi.json.encodeToString(AuthFrame.serializer(), AuthFrame(token)))
			}

			override fun onMessage(webSocket: WebSocket, text: String) {
				if (gen != generation || stopped) return
				handleFrame(text)
			}

			override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
				webSocket.close(code, reason)
			}

			override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
				if (gen != generation || stopped) return
				if (code in AUTH_CLOSE_CODES) {
					terminal = true
					listener?.onTerminalError(ZooRemoteApp.tr(R.string.err_wrong_token_ws, code))
				} else {
					listener?.onDisconnected(code, reason)
					scheduleReconnect()
				}
			}

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
				if (gen != generation || stopped) return
				val fingerprintError = t.causeChain().firstOrNull { it is FingerprintMismatchException } as? FingerprintMismatchException
				if (fingerprintError != null) {
					terminal = true
					listener?.onTerminalError(fingerprintError.message ?: ZooRemoteApp.tr(R.string.err_cert_changed_short))
				} else {
					listener?.onDisconnected(-1, t.message.orEmpty())
					scheduleReconnect()
				}
			}
		})
		socket = newSocket
	}

	private fun handleFrame(text: String) {
		val frame = try {
			ZooApi.json.decodeFromString(WsFrame.serializer(), text)
		} catch (_: Exception) {
			return
		}
		when (frame.type) {
			"connected" -> {
				attempt = 0
				listener?.onConnected()
			}

			"status" -> frame.payload?.let { payload ->
				runCatching { ZooApi.json.decodeFromJsonElement(RemoteStatus.serializer(), payload) }
					.onSuccess { listener?.onStatus(it) }
			}

			"message" -> frame.payload?.let { payload ->
				runCatching { ZooApi.json.decodeFromJsonElement(RemoteActivityPayload.serializer(), payload) }
					.onSuccess { listener?.onActivity(it) }
			}

			// Session 9: task switch in the plugin → replace the whole feed with this history.
			"activity_snapshot" -> frame.payload?.let { payload ->
				runCatching { ZooApi.json.decodeFromJsonElement(ListSerializer(RemoteActivityPayload.serializer()), payload) }
					.onSuccess { listener?.onActivitySnapshot(it) }
			}

			else -> Unit // "ping", unknown future types — ignore.
		}
	}

	private fun scheduleReconnect() {
		if (stopped || terminal) return
		val next = attempt + 1
		attempt = next
		listener?.onReconnecting(next)
		// 1 s, 2 s, 4 s, 8 s, 16 s, then capped at 30 s.
		val delayMs = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L shl (next - 1).coerceAtMost(5)))
		reconnectJob?.cancel()
		reconnectJob = scope.launch {
			if (delayMs > 0) delay(delayMs)
			openSocket()
		}
	}

	private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
