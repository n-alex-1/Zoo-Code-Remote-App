package de.xilox.zooremote.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the tool feed redesign (session 11): JSON decoding of `ClineSayTool` payloads,
 * fingerprinting for consecutive-call collapsing, grouping and the path helpers.
 */
class ToolTextTest {

	private fun item(ts: Long, category: String, text: String?, kind: String = "say") =
		RemoteActivityPayload(ts = ts, kind = kind, category = category, text = text)

	/* ------------------------------------------------------------------ *
	 * parse
	 * ------------------------------------------------------------------ */

	@Test
	fun `parses readFile payload with all display fields`() {
		val parsed = ToolText.parse("""{"tool":"readFile","path":"placeadd.php","isOutsideWorkspace":false,"startLine":12}""")!!
		assertEquals("readFile", parsed.tool)
		assertEquals("placeadd.php", parsed.path)
		assertEquals(12, parsed.startLine)
		assertEquals(false, parsed.isOutsideWorkspace)
	}

	@Test
	fun `parses searchFiles payload with regex pattern and match count`() {
		val parsed = ToolText.parse("""{"tool":"searchFiles","path":"placeadd.php","regex":"selectedOrEmpty","filePattern":"*.php","matchCount":7}""")!!
		assertEquals("searchFiles", parsed.tool)
		assertEquals("selectedOrEmpty", parsed.regex)
		assertEquals("*.php", parsed.filePattern)
		assertEquals(7, parsed.matchCount)
	}

	@Test
	fun `reads diff stats from nested diffStats object`() {
		val parsed = ToolText.parse("""{"tool":"appliedDiff","path":"src/app.ts","diffStats":{"added":14,"removed":3}}""")!!
		assertEquals(14, parsed.linesAdded)
		assertEquals(3, parsed.linesRemoved)
	}

	@Test
	fun `counts completed todos`() {
		val parsed = ToolText.parse(
			"""{"tool":"updateTodoList","todos":[{"status":"completed","content":"a"},{"status":"in_progress","content":"b"},{"status":"pending","content":"c"}]}""",
		)!!
		assertEquals(3, parsed.todoTotal)
		assertEquals(1, parsed.todoDone)
	}

	@Test
	fun `falls back to raw first line for truncated JSON`() {
		// The server truncates payloads at 2000 chars mid-object → org.json throws → fallback.
		val parsed = ToolText.parse("""{"tool":"readFile","path":"very/long/file.php","con""")!!
		assertNull(parsed.tool)
		assertEquals("""{"tool":"readFile","path":"very/long/file.php","con""", parsed.rawFirstLine)
	}

	@Test
	fun `treats plain command text as raw line without tool`() {
		val parsed = ToolText.parse("git status --porcelain\n-more lines")!!
		assertNull(parsed.tool)
		assertEquals("git status --porcelain", parsed.rawFirstLine)
	}

	@Test
	fun `returns null for blank text`() {
		assertNull(ToolText.parse(null))
		assertNull(ToolText.parse("   "))
	}

	@Test
	fun `parses object without tool key via fallback without throwing`() {
		val parsed = ToolText.parse("""{"foo":"bar"}""")!!
		assertNull(parsed.tool)
		assertNotNull(parsed.rawFirstLine)
	}

	/* ------------------------------------------------------------------ *
	 * fingerprint
	 * ------------------------------------------------------------------ */

	@Test
	fun `fingerprints identical tool payloads identically and different payloads differently`() {
		val a = ToolText.fingerprint(item(1, "tool", """{"tool":"readFile","path":"x.php"}"""))
		val b = ToolText.fingerprint(item(2, "tool", """{"tool":"readFile","path":"x.php"}"""))
		val c = ToolText.fingerprint(item(3, "tool", """{"tool":"readFile","path":"y.php"}"""))
		assertEquals(a, b)
		assertTrue(a != c)
	}

	@Test
	fun `never groups asks, text or reasoning entries`() {
		assertNull(ToolText.fingerprint(item(1, "tool", """{"tool":"readFile","path":"x.php"}""", kind = "ask")))
		assertNull(ToolText.fingerprint(item(2, "text", "hello world")))
		assertNull(ToolText.fingerprint(item(3, "reasoning", "thinking…")))
		assertNull(ToolText.fingerprint(item(4, "tool", null)))
	}

	@Test
	fun `commands fingerprint by first line`() {
		val a = ToolText.fingerprint(item(1, "command", "git status\nuntracked stuff"))
		val b = ToolText.fingerprint(item(2, "command", "git status\nother output"))
		assertEquals(a, b)
	}

	/* ------------------------------------------------------------------ *
	 * group
	 * ------------------------------------------------------------------ */

	@Test
	fun `collapses consecutive identical tool calls into one group`() {
		val json = """{"tool":"readFile","path":"placeadd.php"}"""
		val items = listOf(
			item(1, "tool", json),
			item(2, "tool", json),
			item(3, "tool", json),
			item(4, "reasoning", "thinking"),
			item(5, "tool", json),
		)
		val groups = ToolText.group(items)

		assertEquals(3, groups.size)
		assertEquals(3, groups[0].count)
		assertEquals(3L, groups[0].representative.ts) // newest entry represents the run
		assertEquals(1, groups[1].count)
		assertEquals(1, groups[2].count)
	}

	@Test
	fun `leaves different calls as separate groups`() {
		val items = listOf(
			item(1, "tool", """{"tool":"readFile","path":"a.php"}"""),
			item(2, "tool", """{"tool":"readFile","path":"b.php"}"""),
		)
		val groups = ToolText.group(items)
		assertEquals(2, groups.size)
		assertEquals(1, groups[0].count)
		assertEquals(1, groups[1].count)
	}

	@Test
	fun `empty input yields no groups`() {
		assertTrue(ToolText.group(emptyList()).isEmpty())
	}

	/* ------------------------------------------------------------------ *
	 * fileName
	 * ------------------------------------------------------------------ */

	@Test
	fun `extracts the file name from windows and unix paths`() {
		assertEquals("placeadd.php", ToolText.fileName("D:/workspace/placeadd.php"))
		assertEquals("xi-display-24", ToolText.fileName("C:\\dev\\xi-display-24\\"))
		assertEquals("", ToolText.fileName(null))
	}
}
