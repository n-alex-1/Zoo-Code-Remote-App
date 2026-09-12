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
	/** Workspace folder of the extension host (session 9); lets the app filter history per workspace. */
	val workspace: String? = null,
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

/* ------------------------------------------------------------------ *
 * Mode & model switching endpoints (session 7)
 * ------------------------------------------------------------------ */

/** `GET /api/modes` entry — a built-in or custom mode. */
@Serializable
data class ModeInfo(
	val slug: String,
	val name: String,
)

/** One provider profile of `GET /api/models`. */
@Serializable
data class ProfileInfo(
	val id: String,
	val name: String,
	val provider: String? = null,
	/** Model configured for this profile (may differ from the live model after an override). */
	val modelId: String? = null,
)

/** `GET /api/modes` response. */
@Serializable
data class ModesResponse(
	val modes: List<ModeInfo> = emptyList(),
)

/** `GET /api/models` response — profiles plus the currently active model id (may be blank). */
@Serializable
data class ModelsResponse(
	val profiles: List<ProfileInfo> = emptyList(),
	val currentModel: String = "",
)

/* ------------------------------------------------------------------ *
 * Session 9 — task history & workspaces endpoints.
 * ------------------------------------------------------------------ */

/** One entry of the task history (`GET /api/tasks`) — newest first, max 50 entries. */
@Serializable
data class RemoteTaskInfo(
	val taskId: String,
	/** Start timestamp (ms). */
	val ts: Long = 0,
	/** Task title, truncated to 200 chars by the server. */
	val task: String = "",
	val mode: String? = null,
	/** "active" | "completed" | "delegated" | "interrupted". */
	val status: String? = null,
	val workspace: String? = null,
)

/** `GET /api/tasks` response. */
@Serializable
data class TasksResponse(
	val tasks: List<RemoteTaskInfo> = emptyList(),
)

/** A recently used VS Code workspace (`GET /api/workspaces`) — newest first, max 10 entries. */
@Serializable
data class WorkspaceInfo(
	/** Workspace/project folder path (for `code <path>`). */
	val path: String,
	val name: String? = null,
)

/** `GET /api/workspaces` response. */
@Serializable
data class WorkspacesResponse(
	val workspaces: List<WorkspaceInfo> = emptyList(),
)
