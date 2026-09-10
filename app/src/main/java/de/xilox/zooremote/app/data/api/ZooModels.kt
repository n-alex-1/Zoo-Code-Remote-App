package de.xilox.zooremote.app.data.api

import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of the Zoo Code remote API contract (v1).
 *
 * Keep in sync with `zoo-code/src/core/remote/types.ts` and section 3 of
 * `docs/architektur.md` in the zoo-remote workspace. Unknown JSON fields are
 * ignored by [ZooApi.json], so the plugin may add fields without breaking older apps.
 */

/** `GET /api/status` response and payload of WS `status` events. */
@Serializable
data class RemoteStatus(
	val connection: ConnectionInfo,
	val task: TaskState,
	val mode: CurrentMode,
	val model: ModelRef,
)

@Serializable
data class ConnectionInfo(
	val extensionVersion: String = "",
	val apiVersion: String = "1",
)

/** Task block of [RemoteStatus]. `state` is one of idle | running | waiting_for_input | completed | error. */
@Serializable
data class TaskState(
	val state: String,
	val taskId: String? = null,
	/** Max 500 chars, no file contents (per contract). */
	val summary: String? = null,
	val contextWindow: ContextWindow? = null,
	val pendingAsk: PendingAsk? = null,
)

@Serializable
data class ContextWindow(
	val used: Long = 0,
	val limit: Long? = null,
	/** used/limit in 0..100 with two decimals; only present when both values are known. */
	val percent: Double? = null,
)

@Serializable
data class PendingAsk(
	val askType: String,
	val question: String? = null,
	val canApprove: Boolean = false,
	val expectsText: Boolean = false,
	/** Only for `askType == "followup"`. */
	val suggestions: List<RemoteSuggestion>? = null,
)

/** Feed entry as held by the UI layer (alias of [RemoteActivityPayload]). */
typealias ActivityItem = RemoteActivityPayload

@Serializable
data class RemoteSuggestion(
	val answer: String,
	val mode: String? = null,
)

/** Slender ClineMessage extract for the app's chat feed — payload of WS `message` events. */
@Serializable
data class RemoteActivityPayload(
	/** Identity of the line; a payload with an already-seen ts replaces that line (streaming). */
	val ts: Long,
	/** "say" | "ask". */
	val kind: String = "say",
	/** ClineSay/ClineAsk value ("text", "reasoning", "completion_result", "tool", "command", "error", ...). */
	val category: String = "",
	/** Markdown, max 2000 chars; no file contents (per contract). */
	val text: String? = null,
	/** true = streaming chunk that replaces the line with the same ts. */
	val partial: Boolean? = null,
	/** Only for kind="ask": whether the ask has been answered already. */
	val answered: Boolean? = null,
)

@Serializable
data class CurrentMode(
	val current: String = "",
	val label: String = "",
)

@Serializable
data class ModelRef(
	val profileName: String? = null,
	val modelId: String? = null,
	val provider: String? = null,
) {
	/** Human-readable "profile / model" line for UI display. */
	fun describe(): String = buildString {
		profileName?.let { append(it) }
		modelId?.let { if (isNotEmpty()) append(" · "); append(it) }
		if (isEmpty()) append("(unbekannt)")
	}
}
