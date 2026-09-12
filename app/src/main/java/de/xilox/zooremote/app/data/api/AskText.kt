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

	/**
		* Suggestion chips for a followup ask (session 8b UI fix): parsed from the raw JSON text
		* (`{ "question": "...", "suggest": [{ "answer": "...", "mode"? }] }`, same shape as
		* `parseFollowUpSuggestions` on the server). Returns an empty list for anything else — never throws.
		*/
	fun suggestions(category: String, raw: String?): List<RemoteSuggestion> {
		if (category != "followup" || raw.isNullOrBlank()) return emptyList()
		val trimmed = raw.trim()
		if (!trimmed.startsWith("{")) return emptyList()
		return runCatching {
			val arr = org.json.JSONObject(trimmed).optJSONArray("suggest") ?: return@runCatching emptyList()
			(0 until minOf(arr.length(), 4)).mapNotNull { i ->
				val entry = arr.optJSONObject(i) ?: return@mapNotNull null
				val answer = entry.optString("answer").trim()
				if (answer.isEmpty()) return@mapNotNull null
				RemoteSuggestion(answer, entry.optString("mode").takeIf { it.isNotBlank() })
			}
		}.getOrDefault(emptyList())
	}

	private fun truncate(text: String): String = if (text.length > MAX_CHARS) text.take(MAX_CHARS - 1) + "…" else text
}
