package dev.harness.coughtopause.adventure

import dev.harness.coughtopause.rpg.Campaign
import dev.harness.coughtopause.rpg.GameEvent
import dev.harness.coughtopause.rpg.Location
import dev.harness.coughtopause.rpg.Npc
import dev.harness.coughtopause.rpg.OutcomeFact
import dev.harness.coughtopause.rpg.OutcomeFacts
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP seam so the narrator can be tested without a network. */
interface HttpTransport {
    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResult
}

data class HttpResult(val code: Int, val body: String)

class UrlConnectionTransport : HttpTransport {
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            this.connectTimeout = connectTimeoutMs
            this.readTimeout = readTimeoutMs
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Prompt construction and response parsing, kept pure so the contract can be tested without a
 * network call.
 *
 * The model is asked to *narrate* what the engine already decided. It is never asked for dice,
 * difficulty, rewards or new world facts, and anything it returns is treated as prose only.
 */
object PromptBuilder {

    const val MAX_LINES = 4
    const val MAX_LINE_CHARS = 240

    fun systemPrompt(): String = """
        You are Andy, the dungeon master of a small fantasy adventure.
        Narrate only what the engine reports. Never invent dice results, difficulty numbers,
        items, gold, HP changes, new characters or new world facts. Who hit, who missed, who fell
        and what was found are exactly as listed in the engine results; never add or change one.
        Reply with 2 to 4 short sentences, one per line, plain text, no markdown, no lists,
        no quotation marks around the whole reply. Second person, present tense.
    """.trimIndent()

    fun userPrompt(
        campaign: Campaign,
        events: List<GameEvent>,
        preferLocationFirst: Boolean,
        includePlace: Boolean = true,
    ): String {
        val location = campaign.world.locations[campaign.currentLocationId]
        val npcs = campaign.world.npcs.values.filter { it.locationId == campaign.currentLocationId }
        val exits = location?.connections.orEmpty().mapNotNull { campaign.world.locations[it]?.name }
        val facts = eventFacts(campaign, events)

        return buildString {
            appendLine("Player: ${campaign.player.name}, ${campaign.player.background.value.replace('_', ' ')}.")
            appendLine("HP ${campaign.player.vitals.hp}/${campaign.player.vitals.maxHp}, ${campaign.player.gold} gold.")
            appendLine("Mana ${campaign.player.vitals.mp}/${campaign.player.vitals.maxMp}.")
            if (includePlace) {
                appendLine("Location: ${location?.name ?: "unknown"}. ${location?.description.orEmpty()}")
                if (npcs.isNotEmpty()) {
                    appendLine("Present: " + npcs.joinToString("; ") { "${it.name}, ${it.role}. ${it.description}" })
                }
                if (exits.isNotEmpty()) appendLine("Exits: " + exits.joinToString(", "))
            } else {
                // The player has already seen the room: do not read it out again.
                appendLine("Location: ${location?.name ?: "unknown"} (already described; do not describe it again).")
            }
            if (facts.isNotEmpty()) {
                appendLine("Engine results (authoritative, do not change):")
                facts.forEach { appendLine("- $it") }
            }
            appendLine(
                when {
                    !includePlace -> "Narrate only the result. Do not describe the room."
                    preferLocationFirst -> "Describe the place first, then the result."
                    else -> "Describe the result first, then the place if it matters."
                },
            )
        }
    }

    /**
     * Factual one-liners from committed events; the model may phrase them, not alter them.
     *
     * Built from the same [OutcomeFacts] the scripted narrator speaks, so every mechanical result —
     * checks, attacks, enemy swings and misses, damage, spells, Identify entries, items, loot, XP,
     * defeat and recovery — reaches the model exactly as the engine committed it. A model told only
     * "an encounter started" would have to invent who hit whom; with these facts it never needs to.
     */
    fun eventFacts(campaign: Campaign, events: List<GameEvent>): List<String> =
        OutcomeFacts.from(campaign, events).map(::factLine)

    fun factLine(fact: OutcomeFact): String = when (fact) {
        is OutcomeFact.CheckRolled -> {
            val dice = if (fact.natural != null) {
                "rolled ${fact.natural}, total ${fact.total} against DC ${fact.dc}"
            } else {
                "no roll was needed"
            }
            "${fact.skill} check (${fact.intent}): $dice, outcome ${fact.outcome.name.lowercase()}."
        }
        is OutcomeFact.AttackRolled ->
            "Player attacked ${fact.target}: rolled ${fact.natural}, total ${fact.total} against defense " +
                "${fact.defense}, ${if (fact.hit) "hit" else "miss"}."
        is OutcomeFact.EnemyHurt ->
            "${fact.target} took ${fact.amount} damage from the player's ${if (fact.bySpell) "spell" else "weapon"}; " +
                if (fact.felled) "it is defeated." else "${fact.hpAfter} HP left."
        is OutcomeFact.EnemyAttacked ->
            "${fact.attacker} attacked the player: rolled ${fact.natural}, total ${fact.total} against defense " +
                "${fact.defense}, ${if (fact.hit) "hit" else "miss"}."
        is OutcomeFact.PlayerHurt -> "Player took ${fact.amount} damage from ${fact.attacker}; ${fact.hpAfter} HP left."
        is OutcomeFact.Guarded -> "Player defended: +${fact.bonus} defense this round."
        is OutcomeFact.SpellCast ->
            "Player cast ${fact.spell}" + (fact.target?.let { " at $it" } ?: "") + "; ${fact.mpAfter} mana left."
        is OutcomeFact.CreatureIdentified ->
            "Identify on ${fact.creature} (${if (fact.learned) "new entry, ${fact.mpAfter} mana left" else "already known, free"}): " +
                "level ${fact.level} ${fact.threat.name.lowercase()}, ${fact.maxHp} max HP, possible drops: " +
                (fact.possibleDrops.joinToString { "${it.item} ${it.chancePercent}%" }.ifEmpty { "none" }) + "."
        is OutcomeFact.ItemUsed -> "Player used ${fact.item}; ${fact.hpAfter} HP now, ${fact.remaining} left."
        is OutcomeFact.FightStarted ->
            "A fight started against " + fact.enemies.joinToString { "${it.name} (${it.hp}/${it.maxHp} HP)" } + "."
        is OutcomeFact.FightEnded -> {
            val loot = buildList {
                if (fact.gold > 0) add("${fact.gold} gold")
                fact.loot.forEach { (name, count) -> add("$name x$count") }
            }
            "Fight ended: ${fact.status.name.lowercase()}. Loot: ${loot.joinToString().ifEmpty { "none" }}."
        }
        is OutcomeFact.Recovered ->
            "Player was defeated and woke at ${fact.location} with ${fact.hp} HP, ${fact.goldLost} gold lost."
        is OutcomeFact.XpGained ->
            if (fact.levelAfter == fact.levelBefore) {
                "${fact.skill} gained ${fact.amount} XP."
            } else {
                "${fact.skill} gained ${fact.amount} XP (level ${fact.levelBefore} to ${fact.levelAfter})."
            }
        is OutcomeFact.XpWithheld -> "${fact.skill} gained no XP (${fact.reason.name.lowercase().replace('_', ' ')})."
        is OutcomeFact.Travelled -> "Travelled to ${fact.destination}."
        is OutcomeFact.GoldSpent -> "Spent ${fact.amount} gold on ${fact.purpose}; ${fact.balanceAfter} gold left."
        is OutcomeFact.FactLearned -> "Learned (committed world fact): ${fact.text}"
    }

    fun requestJson(config: AiConfig, system: String, user: String): String = JSONObject().apply {
        put("model", config.model)
        put("temperature", 0.8)
        put("max_tokens", 220)
        put("stream", false)
        put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            },
        )
    }.toString()

    /** Extracts `choices[0].message.content`, or null when the shape is not what we expect. */
    fun parseContent(responseJson: String): String? = try {
        JSONObject(responseJson)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    } catch (e: Exception) {
        null
    }

    /** Cleans model prose into narration lines: no fences, bullets, quotes or empty lines. */
    fun toLines(content: String): List<String> = content
        .lineSequence()
        .map { it.trim() }
        // Drop fence lines entirely, including a language tag like ```text.
        .filterNot { it.startsWith("```") }
        .map { it.removePrefix("- ").removePrefix("* ").trim() }
        .map { it.trim('"', '\'', ' ') }
        .filter { it.isNotEmpty() }
        .map { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) else it }
        .take(MAX_LINES)
        .toList()
}

/**
 * The optional AI dungeon master.
 *
 * Returns null on any failure (not configured, HTTP error, unreadable body, empty prose) so the
 * caller falls back to the deterministic narrator and the UI can say which one spoke.
 */
class LlmNarrator(
    private val config: AiConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
) : NarrativeProvider {

    override val id = "llm"

    override val status: String
        get() = if (config.configured) {
            "Andy · ${config.model} via ${hostOf(config.baseUrl)}"
        } else {
            "Andy · AI dungeon master not configured (add a key in AI settings)"
        }

    @Volatile
    var lastError: String? = null
        private set

    override fun narrateScene(
        campaign: Campaign,
        events: List<GameEvent>,
        preferLocationFirst: Boolean,
        includePlace: Boolean,
    ): List<String>? {
        val content = request(
            PromptBuilder.systemPrompt(),
            PromptBuilder.userPrompt(campaign, events, preferLocationFirst, includePlace),
        ) ?: return null
        val lines = PromptBuilder.toLines(content)
        if (lines.isEmpty()) {
            lastError = "empty narration"
            return null
        }
        return lines
    }

    /** One tiny round trip, used by the settings screen to prove the key works. */
    fun probe(): String? = request(
        "You are a concise assistant.",
        "Reply with one short sentence about a lantern.",
    )?.trim()

    private fun request(system: String, user: String): String? {
        if (!config.configured) {
            lastError = "not configured"
            return null
        }
        val body = PromptBuilder.requestJson(config, system, user)
        val result = runCatching {
            transport.post(
                url = config.completionsUrl,
                headers = mapOf(
                    "Authorization" to "Bearer ${config.apiKey}",
                    "Content-Type" to "application/json",
                ),
                body = body,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 30_000,
            )
        }.getOrElse { error ->
            lastError = error.javaClass.simpleName
            return null
        }
        if (result.code !in 200..299) {
            lastError = "HTTP ${result.code}"
            return null
        }
        val content = PromptBuilder.parseContent(result.body)
        if (content == null) {
            lastError = "unreadable response"
            return null
        }
        lastError = null
        return content
    }

    override fun describeLocation(location: Location, npcs: List<Npc>, exits: List<String>): List<String>? = null

    override fun describeOutcome(campaign: Campaign, events: List<GameEvent>): List<String>? = null

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
}
