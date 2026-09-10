package de.xilox.zooremote.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing tests for the remote API contract (session 7) against example payloads derived from
 * `docs/architektur.md` section 3 and `zoo-code/src/core/remote/types.ts`. Uses [ZooApi.json] so
 * the exact production configuration (ignoreUnknownKeys) is covered.
 */
class ZooModelsParsingTest {

	private val json = ZooApi.json

	/* ------------------------------------------------------------------ *
	 * RemoteStatus (GET /api/status, WS "status" events, action responses)
	 * ------------------------------------------------------------------ */

	@Test
	fun `parses full status payload with pending ask and context window`() {
		val raw = """
			{
			  "connection": { "extensionVersion": "0.3.0", "apiVersion": "1" },
			  "task": {
			    "state": "waiting_for_input",
			    "taskId": "task-42",
			    "summary": "Implement the login screen",
			    "contextWindow": { "used": 183500, "limit": 200000, "percent": 91.75 },
			    "pendingAsk": {
			      "askType": "followup",
			      "question": "{\"question\":\"Soll ich weitermachen?\",\"suggestions\":[{\"answer\":\"Ja, weiter\"},{\"answer\":\"Stoppe hier\",\"mode\":\"architect\"}]}",
			      "canApprove": false,
			      "expectsText": true,
			      "suggestions": [ { "answer": "Ja, weiter" }, { "answer": "Stoppe hier", "mode": "architect" } ]
			    }
			  },
			  "mode": { "current": "code", "label": "Code" },
			  "model": { "profileName": "OpenRouter Default", "modelId": "anthropic/claude-sonnet-4", "provider": "openrouter" }
			}
		""".trimIndent()

		val status = json.decodeFromString(RemoteStatus.serializer(), raw)

		assertEquals("0.3.0", status.connection.extensionVersion)
		assertEquals("1", status.connection.apiVersion)
		assertEquals("waiting_for_input", status.task.state)
		assertEquals("task-42", status.task.taskId)
		assertEquals(183_500L, status.task.contextWindow?.used ?: -1L)
		assertEquals(91.75, status.task.contextWindow?.percent ?: -1.0, 0.0001)

		val ask = status.task.pendingAsk!!
		assertEquals("followup", ask.askType)
		assertEquals(true, ask.expectsText)
		assertEquals(false, ask.canApprove)
		assertEquals(2, ask.suggestions?.size ?: -1)
		assertEquals("architect", ask.suggestions?.get(1)?.mode)

		assertEquals("code", status.mode.current)
		assertEquals("OpenRouter Default · anthropic/claude-sonnet-4", status.model.describe())
	}

	@Test
	fun `parses minimal idle status without optional blocks`() {
		val raw = """
			{
			  "connection": { "extensionVersion": "", "apiVersion": "1" },
			  "task": { "state": "idle" },
			  "mode": { "current": "architect", "label": "Architekt" },
			  "model": {}
			}
		""".trimIndent()

		val status = json.decodeFromString(RemoteStatus.serializer(), raw)

		assertEquals("idle", status.task.state)
		assertNull(status.task.taskId)
		assertNull(status.task.pendingAsk)
		assertNull(status.task.contextWindow)
		assertEquals("(unbekannt)", status.model.describe())
	}

	@Test
	fun `ignores unknown fields added by newer plugin versions`() {
		val raw = """
			{
			  "connection": { "extensionVersion": "9.9.9", "apiVersion": "1", "newField": true },
			  "task": { "state": "running", "brandNew": 42 },
			  "mode": { "current": "code", "label": "Code" },
			  "model": { "profileName": "P", "future": null }
			}
		""".trimIndent()

		val status = json.decodeFromString(RemoteStatus.serializer(), raw)
		assertEquals("running", status.task.state)
		assertEquals("code", status.mode.current)
	}

	@Test
	fun `decodes action response as fresh RemoteStatus`() {
		// POST /api/mode and POST /api/model answer with the fresh RemoteStatus (contract v1).
		val raw = """
			{
			  "connection": { "extensionVersion": "0.3.0", "apiVersion": "1" },
			  "task": { "state": "idle" },
			  "mode": { "current": "architect", "label": "Architekt" },
			  "model": { "profileName": "Anthropic Default", "modelId": "claude-sonnet-4-20250514", "provider": "anthropic" }
			}
		""".trimIndent()

		val status = json.decodeFromString(RemoteStatus.serializer(), raw)
		assertEquals("architect", status.mode.current)
		assertEquals("Anthropic Default · claude-sonnet-4-20250514", status.model.describe())
	}

	/* ------------------------------------------------------------------ *
	 * GET /api/modes
	 * ------------------------------------------------------------------ */

	@Test
	fun `parses modes response`() {
		val raw = """
			{ "modes": [ { "slug": "code", "name": "Code" }, { "slug": "architect", "name": "Architekt" }, { "slug": "my-mode", "name": "Mein Modus" } ] }
		"""

		val response = json.decodeFromString(ModesResponse.serializer(), raw)

		assertEquals(3, response.modes.size)
		assertEquals("code", response.modes[0].slug)
		assertEquals("Mein Modus", response.modes[2].name)
	}

	@Test
	fun `parses empty modes list`() {
		val response = json.decodeFromString(ModesResponse.serializer(), """{ "modes": [] }""")
		assertEquals(0, response.modes.size)
	}

	/* ------------------------------------------------------------------ *
	 * GET /api/models
	 * ------------------------------------------------------------------ */

	@Test
	fun `parses models response with profiles and current model`() {
		val raw = """
			{
			  "profiles": [
			    { "id": "profile-1", "name": "OpenRouter Default", "provider": "openrouter", "modelId": "anthropic/claude-sonnet-4" },
			    { "id": "profile-2", "name": "Anthropic Local", "provider": "anthropic", "modelId": "claude-sonnet-4-20250514" }
			  ],
			  "currentModel": "anthropic/claude-sonnet-4"
			}
		""".trimIndent()

		val response = json.decodeFromString(ModelsResponse.serializer(), raw)

		assertEquals(2, response.profiles.size)
		assertEquals("profile-1", response.profiles[0].id)
		assertEquals("openrouter", response.profiles[0].provider)
		assertEquals("anthropic/claude-sonnet-4", response.currentModel)
	}

	@Test
	fun `parses models response with minimal profile entries`() {
		// Provider/modelId are optional per ProfileInfo — older profiles may omit them.
		val raw = """{ "profiles": [ { "id": "p1", "name": "Legacy" } ], "currentModel": "" }"""

		val response = json.decodeFromString(ModelsResponse.serializer(), raw)

		assertEquals("Legacy", response.profiles[0].name)
		assertNull(response.profiles[0].provider)
		assertNull(response.profiles[0].modelId)
	}

	/* ------------------------------------------------------------------ *
	 * AskText (shared question extraction, session 6 — regression guard)
	 * ------------------------------------------------------------------ */

	@Test
	fun `ask text extracts question from followup json and truncates long ones`() {
		val raw = """{"question":"Soll ich fortfahren?","suggestions":[]}"""
		assertEquals("Soll ich fortfahren?", AskText.question("followup", raw))

		val long = "x".repeat(400)
		assertEquals(300, AskText.question("followup", "{\"question\":\"$long\"}")?.length ?: -1)

		assertNull(AskText.question("tool", null))
	}
}
