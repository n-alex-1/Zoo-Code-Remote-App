package de.xilox.zooremote.app.data.api

/**
 * Shared extraction of the human-readable question from an ask's raw text. Follow-up asks carry
 * JSON (`{ "question": "...", "suggest": [...] }`, same shape the webview parses); everything else
 * is plain markdown/text. Used by both the activity feed (session 5) and notifications (session 6).
 */
object AskText {

	private const val MAX_CHARS = 300

	fun question(category: String, raw: String?): String? {
		if (raw.isNullOrBlank()) return null
		val trimmed = raw.trim()
		if (category == "followup" && trimmed.startsWith("{")) {
			runCatching {
				org.json.JSONObject(trimmed).optString("question").takeIf { it.isNotBlank() }
			}.getOrNull()?.let { return truncate(it) }
		}
		return truncate(trimmed)
	}

	private fun truncate(text: String): String = if (text.length > MAX_CHARS) text.take(MAX_CHARS - 1) + "…" else text
}
