package de.xilox.zooremote.app.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException

/** Thrown for non-2xx HTTP responses; [httpCode] lets callers distinguish e.g. 401 (wrong token). */
class ZooApiException(
	message: String,
	val httpCode: Int? = null,
	val body: String? = null,
) : IOException(message)

/**
 * Minimal REST client for the Zoo Code remote server (API contract v1 — section 3 of
 * `docs/architektur.md`). Deliberately synchronous: callers must run it on a background
 * dispatcher (`Dispatchers.IO`) so the main thread never blocks.
 */
class ZooApi(
	private val client: OkHttpClient,
	/** Base URL without trailing slash, e.g. `https://192.168.1.42:8999`. */
	private val baseUrl: String,
	private val token: String,
) {
	companion object {
		val json = Json { ignoreUnknownKeys = true }
	}

	@Serializable
	data class HealthResponse(val ok: Boolean = false, val version: String? = null)

	/** Body of `POST /api/ask/respond`. `text` is required for `messageResponse`. */
	@Serializable
	data class AskRespondCommand(val response: String, val text: String? = null)

	/** Body of `POST /api/mode`. */
	@Serializable
	data class ModeCommand(val slug: String)

	@Serializable
	private data class OkResult(val ok: Boolean = false, val error: String? = null)

	/** `GET /api/health` — unauthenticated per contract (token header is sent anyway; harmless). */
	fun health(): HealthResponse = get("api/health", HealthResponse.serializer())

	/** `GET /api/status` — bearer token required. */
	fun status(): RemoteStatus = get("api/status", RemoteStatus.serializer())

	/**
	 * `POST /api/ask/respond` — answers the active task's pending ask via the same code path as
	 * the webview (`yesButtonClicked`, `noButtonClicked`, or `messageResponse` + [text]).
	 */
	fun respondToAsk(response: String, text: String? = null) {
		val result = post("api/ask/respond", AskRespondCommand(response, text), AskRespondCommand.serializer(), OkResult.serializer())
		if (!result.ok && !result.error.isNullOrBlank()) throw ZooApiException(result.error.orEmpty(), body = result.error)
	}

	/** `POST /api/mode` — switches the extension's mode by slug. */
	fun setMode(slug: String) {
		val result = post("api/mode", ModeCommand(slug), ModeCommand.serializer(), OkResult.serializer())
		if (!result.ok && !result.error.isNullOrBlank()) throw ZooApiException(result.error.orEmpty(), body = result.error)
	}

	private fun <T> get(path: String, deserializer: KSerializer<T>): T {
		val request = Request.Builder()
			.url("$baseUrl/$path")
			.header("Authorization", "Bearer $token")
			.get()
			.build()
		client.newCall(request).execute().use { res ->
			val body = res.body?.string().orEmpty()
			if (!res.isSuccessful) {
				throw ZooApiException("HTTP ${res.code} von /$path", res.code, body.take(300))
			}
			return json.decodeFromString(deserializer, body)
		}
	}

	private fun <B, T> post(path: String, body: B, bodySerializer: KSerializer<B>, deserializer: KSerializer<T>): T {
		val payload = json.encodeToString(bodySerializer, body).toRequestBody("application/json".toMediaType())
		val request = Request.Builder()
			.url("$baseUrl/$path")
			.header("Authorization", "Bearer $token")
			.post(payload)
			.build()
		client.newCall(request).execute().use { res ->
			val responseBody = res.body?.string().orEmpty()
			if (!res.isSuccessful) {
				throw ZooApiException("HTTP ${res.code} von /$path", res.code, responseBody.take(300))
			}
			return json.decodeFromString(deserializer, responseBody)
		}
	}
}
