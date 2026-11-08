package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.Backgrounds
import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.CampaignId
import dev.harness.coughtopause.rpg.CreateCampaignResult
import dev.harness.coughtopause.rpg.Validated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiConfigTest {

    @Test
    fun configuredRequiresAllThreeFields() {
        assertFalse(AiConfig(apiKey = "").configured)
        assertFalse(AiConfig(baseUrl = "", apiKey = "k").configured)
        assertFalse(AiConfig(model = "", apiKey = "k").configured)
        assertTrue(AiConfig(apiKey = "k").configured)
    }

    @Test
    fun completionsUrlToleratesBothBaseForms() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig(baseUrl = "https://api.deepseek.com/v1").completionsUrl,
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig(baseUrl = "https://api.deepseek.com/v1/").completionsUrl,
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig(baseUrl = "https://api.deepseek.com/v1/chat/completions").completionsUrl,
        )
    }
}

class PromptBuilderTest {

    @Test
    fun requestCarriesModelAndBothMessages() {
        val json = PromptBuilder.requestJson(
            AiConfig(model = "deepseek-flash", apiKey = "secret"),
            "system text",
            "user text",
        )
        assertTrue(json.contains("\"model\":\"deepseek-flash\""))
        assertTrue(json.contains("system text"))
        assertTrue(json.contains("user text"))
        assertFalse("the key must never be in the request body", json.contains("secret"))
    }

    @Test
    fun parsesTheOpenAiCompatibleShape() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Line one.\nLine two."}}]}"""
        assertEquals("Line one.\nLine two.", PromptBuilder.parseContent(body))
    }

    @Test
    fun unreadableBodiesReturnNull() {
        assertNull(PromptBuilder.parseContent("not json"))
        assertNull(PromptBuilder.parseContent("""{"error":{"message":"bad key"}}"""))
        assertNull(PromptBuilder.parseContent(""))
    }

    @Test
    fun proseIsCleanedIntoNarrationLines() {
        val content = """
            ```text
            - "The lantern gutters."
            * You hear the door.
            
            A gull cries.
            ```
        """.trimIndent()
        val lines = PromptBuilder.toLines(content)
        assertEquals(listOf("The lantern gutters.", "You hear the door.", "A gull cries."), lines)
    }

    @Test
    fun linesAreCapped() {
        val content = (1..10).joinToString("\n") { "Line $it." }
        assertEquals(PromptBuilder.MAX_LINES, PromptBuilder.toLines(content).size)
        val long = "x".repeat(500)
        assertEquals(PromptBuilder.MAX_LINE_CHARS, PromptBuilder.toLines(long).first().length)
    }

    @Test
    fun userPromptCarriesEngineFactsAndForbidsInvention() {
        val campaign = campaign()
        val prompt = PromptBuilder.userPrompt(campaign, emptyList(), preferLocationFirst = true)
        assertTrue(prompt.contains("Player: Mira"))
        assertTrue(prompt.contains("Location:"))
        assertTrue(prompt.contains("Describe the place first"))
    }

    private fun campaign(): Campaign {
        val engine = AdventureEngine(File("build/tmp/ai-test-${System.nanoTime()}"), clock = { 1L })
        val rolled = engine.rollAttributes(11L)
        val assignment = engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Mira", rolled, assignment, Backgrounds.CARAVAN_GUARD)
        val created = engine.createCampaign(
            CampaignId("c-ai"),
            11L,
            (character as Validated.Valid).value,
        )
        return (created as CreateCampaignResult.Created).campaign
    }
}

class LlmNarratorTest {

    private class FakeTransport(
        private val result: HttpResult? = null,
        private val failure: Throwable? = null,
    ) : HttpTransport {
        var lastUrl: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResult {
            lastUrl = url
            lastBody = body
            lastHeaders = headers
            failure?.let { throw it }
            return result ?: HttpResult(200, "{}")
        }
    }

    private fun campaign(): Campaign {
        val engine = AdventureEngine(File("build/tmp/llm-test-${System.nanoTime()}"), clock = { 1L })
        val rolled = engine.rollAttributes(3L)
        val assignment = engine.autoAssign(rolled, Backgrounds.CARAVAN_GUARD)
        val character = engine.createCharacter("Mira", rolled, assignment, Backgrounds.CARAVAN_GUARD)
        return (engine.createCampaign(
            CampaignId("c-llm"),
            3L,
            (character as Validated.Valid).value,
        ) as CreateCampaignResult.Created).campaign
    }

    @Test
    fun successfulResponseBecomesNarrationLines() {
        val transport = FakeTransport(
            HttpResult(200, """{"choices":[{"message":{"content":"The lantern gutters.\nA gull cries."}}]}"""),
        )
        val narrator = LlmNarrator(AiConfig(apiKey = "k"), transport)
        val lines = narrator.narrateScene(campaign(), emptyList(), preferLocationFirst = false)
        assertEquals(listOf("The lantern gutters.", "A gull cries."), lines)
        assertEquals("https://api.deepseek.com/v1/chat/completions", transport.lastUrl)
        assertEquals("Bearer k", transport.lastHeaders["Authorization"])
        assertNull(narrator.lastError)
    }

    @Test
    fun httpErrorFallsBackWithAReason() {
        val narrator = LlmNarrator(AiConfig(apiKey = "k"), FakeTransport(HttpResult(401, "{}")))
        assertNull(narrator.narrateScene(campaign(), emptyList(), false))
        assertEquals("HTTP 401", narrator.lastError)
    }

    @Test
    fun transportFailureFallsBackWithAReason() {
        val narrator = LlmNarrator(
            AiConfig(apiKey = "k"),
            FakeTransport(failure = java.net.UnknownHostException("offline")),
        )
        assertNull(narrator.narrateScene(campaign(), emptyList(), false))
        assertEquals("UnknownHostException", narrator.lastError)
    }

    @Test
    fun unconfiguredNarratorNeverCallsTheNetwork() {
        val transport = FakeTransport(HttpResult(200, "{}"))
        val narrator = LlmNarrator(AiConfig(apiKey = ""), transport)
        assertNull(narrator.narrateScene(campaign(), emptyList(), false))
        assertNull(transport.lastUrl)
        assertEquals("not configured", narrator.lastError)
    }

    @Test
    fun statusNeverContainsTheKey() {
        val narrator = LlmNarrator(AiConfig(apiKey = "super-secret"), FakeTransport())
        assertFalse(narrator.status.contains("super-secret"))
        assertTrue(narrator.status.contains("deepseek-flash"))
    }

    @Test
    fun probeReportsSuccessAndFailure() {
        val ok = LlmNarrator(
            AiConfig(apiKey = "k"),
            FakeTransport(HttpResult(200, """{"choices":[{"message":{"content":"A lantern."}}]}""")),
        )
        assertEquals("A lantern.", ok.probe())

        val bad = LlmNarrator(AiConfig(apiKey = "k"), FakeTransport(HttpResult(500, "{}")))
        assertNull(bad.probe())
        assertNotNull(bad.lastError)
    }
}
