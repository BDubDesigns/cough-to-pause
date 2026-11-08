package dev.harness.coughtopause.rpg

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface LoadResult {
    data class Loaded(val campaign: Campaign) : LoadResult
    data object Missing : LoadResult
    data class Unreadable(val error: PersistenceError) : LoadResult
}

/**
 * The persistence boundary. [save] must be atomic: after a crash at any point, [load] returns
 * either the complete previous campaign or the complete new one, never a mixture.
 */
interface CampaignStore {
    fun load(id: CampaignId): LoadResult
    fun save(campaign: Campaign)
    fun exists(id: CampaignId): Boolean
    fun list(): List<CampaignId>
}

/**
 * One JSON document per campaign, replaced atomically: write `<id>.json.tmp`, fsync it, then
 * rename over `<id>.json`. A whole campaign commit (state + rewards + events + idempotency
 * receipt) is therefore a single all-or-nothing file replacement.
 *
 * Fine for M1-sized campaigns (a few hundred events). If history grows large, swap in an
 * SQLite/Room implementation of [CampaignStore]; callers do not change.
 *
 * On Android pass `File(context.filesDir, "campaigns")`. [beforeReplace] exists only so tests
 * can simulate a crash between writing and committing.
 */
class FileCampaignStore(
    private val directory: File,
    private val beforeReplace: (temp: File) -> Unit = {},
) : CampaignStore {

    override fun load(id: CampaignId): LoadResult {
        val file = fileFor(id)
        if (!file.exists()) return LoadResult.Missing
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return LoadResult.Unreadable(PersistenceError.Corrupt(e.message ?: "read failed"))
        }
        return when (val decoded = CampaignCodec.decode(text)) {
            is Validated.Valid -> LoadResult.Loaded(decoded.value)
            is Validated.Invalid -> LoadResult.Unreadable(decoded.errors.first())
        }
    }

    override fun save(campaign: Campaign) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val bytes = CampaignCodec.encode(campaign).toByteArray(Charsets.UTF_8)
        val temp = File(directory, "${campaign.id.value}$TEMP_SUFFIX")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        beforeReplace(temp)
        Files.move(temp.toPath(), fileFor(campaign.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun exists(id: CampaignId): Boolean = fileFor(id).exists()

    override fun list(): List<CampaignId> =
        directory.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(SUFFIX) }
            .mapNotNull { name -> name.removeSuffix(SUFFIX).takeIf { SAFE_ID.matches(it) }?.let(::CampaignId) }
            .sortedBy { it.value }

    private fun fileFor(id: CampaignId) = File(directory, "${id.value}$SUFFIX")

    private companion object {
        const val SUFFIX = ".json"
        const val TEMP_SUFFIX = ".json.tmp"
    }
}

sealed interface Submission {
    /** Newly applied and durably saved. */
    data class Committed(val events: List<GameEvent>) : Submission

    /** Already applied earlier (retry, restart, narration replay). Nothing changed. */
    data class Replayed(val events: List<GameEvent>) : Submission

    /** Refused by the rules. Nothing changed. */
    data class Rejected(val errors: List<DomainError>) : Submission

    /** The campaign could not be loaded. Nothing changed. */
    data class Unavailable(val reason: String) : Submission
}

sealed interface CreateCampaignResult {
    data class Created(val campaign: Campaign) : CreateCampaignResult
    data class Rejected(val errors: List<DomainError>) : CreateCampaignResult
    data object AlreadyExists : CreateCampaignResult
}

/**
 * The single authoritative entry point: load → rules → atomic save. Callers (the audio
 * orchestrator, later the AI adapter) hand it typed commands and narrate the returned events.
 *
 * Exactly-once: the idempotency receipt is saved in the same atomic write as the change it guards.
 * - Crash before the save → nothing happened; resubmitting the same action id applies it once,
 *   with the same dice, because the action's RNG is derived from (campaign seed, action ordinal, id).
 * - Crash after the save → resubmitting returns [Submission.Replayed] with the original events.
 */
class ActionProcessor(
    private val store: CampaignStore,
    private val rules: Rules = Rules(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val randomFor: (Campaign, GameCommand) -> RandomSource = ::actionRandom,
) {

    @Synchronized
    fun createCampaign(id: CampaignId, seed: Long, character: PlayerCharacter, region: WorldProposal): CreateCampaignResult {
        if (store.exists(id)) return CreateCampaignResult.AlreadyExists
        return when (val result = rules.newCampaign(id, seed, character, region, clock())) {
            is Validated.Invalid -> CreateCampaignResult.Rejected(result.errors)
            is Validated.Valid -> {
                store.save(result.value)
                CreateCampaignResult.Created(result.value)
            }
        }
    }

    @Synchronized
    fun submit(id: CampaignId, command: GameCommand): Submission {
        val campaign = when (val loaded = store.load(id)) {
            is LoadResult.Loaded -> loaded.campaign
            LoadResult.Missing -> return Submission.Unavailable("No campaign '$id'")
            is LoadResult.Unreadable -> return Submission.Unavailable(loaded.error.message)
        }
        return when (val result = rules.process(campaign, command, randomFor(campaign, command), clock())) {
            is RuleResult.Rejected -> Submission.Rejected(result.errors)
            is RuleResult.Replayed -> Submission.Replayed(result.events)
            is RuleResult.Applied -> {
                store.save(result.campaign) // throws on I/O failure: caller retries with the same id
                Submission.Committed(result.events)
            }
        }
    }

    @Synchronized
    fun load(id: CampaignId): LoadResult = store.load(id)
}

/** Reproducible per-action dice: retrying an uncommitted action cannot reroll a bad result. */
fun actionRandom(campaign: Campaign, command: GameCommand): RandomSource = SplitMix64(
    Seeds.combine(campaign.seed, campaign.appliedActions.size.toLong(), Seeds.stableHash(command.actionId.value)),
)
