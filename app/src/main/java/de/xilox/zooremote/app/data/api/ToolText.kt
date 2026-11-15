package de.xilox.zooremote.app.data.api

/**
 * Structured extraction of the raw `tool` feed entries. The plugin sends `ClineSayTool` JSON
 * (`{"tool":"readFile","path":"…","regex":"…"}`) and the app used to print that payload verbatim —
 * long, repetitive and hard to scan on a phone. This parser pulls the meaningful fields out so the
 * feed can render a compact, icon-coded chip (verb + file name, details on a muted second line) and
 * collapse consecutive identical calls.
 *
 * Never throws: truncated payloads (the server caps `text` at 2000 chars) and non-JSON text (plain
 * shell commands) fall back to [Parsed.rawFirstLine] / a non-null [Parsed] with only that line set.
 *
 * Field names mirror `ClineSayTool` in `zoo-code/packages/types/src/vscode-extension-host.ts`.
 */
object ToolText {

	/** One decoded `ClineSayTool` payload; absent fields are null / 0 / false. */
	data class Parsed(
		/** Raw tool id ("readFile", "searchFiles", "editedExistingFile", …); null for non-JSON text. */
		val tool: String? = null,
		/** File/dir path of the operation. */
		val path: String? = null,
		/** Search regex (searchFiles). */
		val regex: String? = null,
		/** Glob filter (searchFiles / listFiles). */
		val filePattern: String? = null,
		/** Semantic query (codebaseSearch). */
		val query: String? = null,
		/** Target mode (switchMode). */
		val mode: String? = null,
		/** Command name / arguments (runSlashCommand). */
		val command: String? = null,
		val args: String? = null,
		/** Skill name (skill tool). */
		val skill: String? = null,
		/** Optional model-provided description (runSlashCommand). */
		val description: String? = null,
		/** Question carried by finishTask / completion asks. */
		val question: String? = null,
		/** Number of matches reported by searchFiles. */
		val matchCount: Int = 0,
		/** Extra files bundled into one read/list call. */
		val additionalFileCount: Int = 0,
		/** Unified diff statistics for edit tools. */
		val linesAdded: Int = 0,
		val linesRemoved: Int = 0,
		/** Line anchor of a read_file / readCommandOutput request. */
		val lineNumber: Int = 0,
		val startLine: Int = 0,
		val isOutsideWorkspace: Boolean = false,
		/** updateTodoList progress. */
		val todoTotal: Int = 0,
		val todoDone: Int = 0,
		/** First non-blank line of the raw text — fallback for unparsable payloads. */
		val rawFirstLine: String? = null,
	)

	/**
	 * Decodes one feed payload. Non-JSON text (a plain `command` say) yields a [Parsed] whose only
	 * field is [Parsed.rawFirstLine]; an unparseable/truncated object yields the same fallback, so
	 * the caller always has something to display.
	 */
	fun parse(raw: String?): Parsed? {
		val trimmed = raw?.trim().orEmpty()
		if (trimmed.isEmpty()) return null
		if (!trimmed.startsWith("{")) return Parsed(rawFirstLine = firstLine(trimmed))
		return runCatching { decode(org.json.JSONObject(trimmed)) }
			.getOrNull() ?: Parsed(rawFirstLine = firstLine(trimmed))
	}

	private fun decode(o: org.json.JSONObject): Parsed {
		val stats = o.optJSONObject("diffStats")
		val todos = o.optJSONArray("todos")
		var done = 0
		if (todos != null) {
			for (i in 0 until todos.length()) {
				if (todos.optJSONObject(i)?.optString("status") == "completed") done++
			}
		}
		return Parsed(
			tool = o.optString("tool").takeIf { it.isNotBlank() },
			path = o.optString("path").takeIf { it.isNotBlank() },
			regex = o.optString("regex").takeIf { it.isNotBlank() },
			filePattern = o.optString("filePattern").takeIf { it.isNotBlank() },
			query = o.optString("query").takeIf { it.isNotBlank() },
			mode = o.optString("mode").takeIf { it.isNotBlank() },
			command = o.optString("command").takeIf { it.isNotBlank() },
			args = o.optString("args").takeIf { it.isNotBlank() },
			skill = o.optString("skill").takeIf { it.isNotBlank() },
			description = o.optString("description").takeIf { it.isNotBlank() },
			question = o.optString("question").takeIf { it.isNotBlank() },
			matchCount = o.optInt("matchCount", 0),
			additionalFileCount = o.optInt("additionalFileCount", 0),
			linesAdded = stats?.optInt("added", 0) ?: 0,
			linesRemoved = stats?.optInt("removed", 0) ?: 0,
			lineNumber = o.optInt("lineNumber", 0),
			startLine = o.optInt("startLine", 0),
			isOutsideWorkspace = o.optBoolean("isOutsideWorkspace", false),
			todoTotal = todos?.length() ?: 0,
			todoDone = done,
			rawFirstLine = firstLine(o.toString()),
		)
	}

	private fun firstLine(text: String): String? = text.lineSequence().firstOrNull { it.isNotBlank() }

	/**
	 * Fingerprint for collapsing *consecutive* identical tool calls into a single "… ×3" row.
	 * Returns null for anything that must never be grouped (asks, text/reasoning/error rows, empty
	 * payloads). Identical JSON means an identical call (same tool, path and arguments), so exact
	 * string equality is the safest key.
	 */
	fun fingerprint(item: ActivityItem): String? {
		if (item.kind != "say") return null
		val category = item.category
		if (category != "tool" && category != "command") return null
		val text = item.text?.trim().orEmpty()
		if (text.isEmpty()) return null
		return if (text.startsWith("{")) "t:" + text else "c:" + firstLine(text).orEmpty()
	}

	/** One rendered feed line: a single entry, or a run of ≥2 consecutive identical tool calls. */
	data class ToolGroup(val items: List<ActivityItem>) {
		/** Newest entry represents the group — later partials supersede earlier ones. */
		val representative: ActivityItem get() = items.last()
		val count: Int get() = items.size
	}

	/** Collapses consecutive tool entries sharing a [fingerprint]; other entries stay single. */
	fun group(items: List<ActivityItem>): List<ToolGroup> {
		if (items.isEmpty()) return emptyList()
		val groups = mutableListOf<ToolGroup>()
		var index = 0
		while (index < items.size) {
			val fp = fingerprint(items[index])
			var end = index + 1
			if (fp != null) {
				while (end < items.size && fingerprint(items[end]) == fp) end++
			}
			groups += ToolGroup(items.subList(index, end))
			index = end
		}
		return groups
	}

	/** Just the file/dir name of a path ("C:/dev/app/x.php" → "x.php", "/srv/ws/" → "ws"). */
	fun fileName(path: String?): String {
		if (path.isNullOrBlank()) return ""
		return path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
	}
}
