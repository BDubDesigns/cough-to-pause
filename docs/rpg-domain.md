# RPG domain foundation (M1, PR 1)

The `:rpg` module holds the authoritative game rules for M1 ([issue #1](https://github.com/BDubDesigns/cough-to-pause/issues/1)).
It is pure Kotlin/JVM: no Android imports compile there, so the rules can be tested without the
Android SDK and cannot depend on platform behaviour.

```sh
./scripts/build.sh :rpg:test     # 66 JVM tests
```

The narrator (LLM) **proposes**; this module **decides**. The narrator cannot roll dice, pick a
difficulty after the roll, grant XP/gold/items, set HP, invent skills, or rewrite committed world
facts. No command for any of those exists.

## Layout

```
rpg/src/main/kotlin/dev/harness/coughtopause/rpg/
  Ids.kt                stable ids, DomainError, Validated<T, E>
  Random.kt             RandomSource, SplitMix64 (fully specified), seed derivation, Dice
  Balance.kt            EVERY provisional number, in one place (see table below)
  Attributes.kt         six attributes, scores, d20 modifier
  CharacterCreation.kt  4d6-drop-lowest roller + safeguard, assignment, backgrounds, CharacterFactory
  Skills.kt             fixed SkillCatalog, ChallengeKey, XpAwardPolicy (anti-repeat)
  Catalog.kt            canonical item and creature templates, loot tables
  Checks.kt             CheckRequest → PreparedCheck (DC fixed) → RollRecord
  Model.kt              Campaign, PlayerCharacter, WorldState, Location, Npc, WorldFact, Encounter
  Events.kt             GameEvent + payloads (the explainable history)
  Commands.kt           GameCommand: the only ways to change a campaign
  Rules.kt              pure reducer: (campaign, command, dice, time) → Applied | Replayed | Rejected
  WorldProposals.kt     strict parser, validator, id assignment, fallback region
  Codec.kt              versioned JSON for campaigns, canonical command fingerprints
  Store.kt              CampaignStore, FileCampaignStore (atomic), ActionProcessor
```

## Contracts

### Character creation
1. `AttributeRoller(safeguard).roll(rng)` rolls six 4d6-drop-lowest scores. Sets failing the
   safeguard are rerolled; rejected sets are kept in `rerolledSets` for transparency. After
   `maxRerolls` the best rejected set is kept and `safeguardExhausted = true` (creation always ends).
2. The player maps each attribute to a rolled-score **index** (`Map<Attribute, Int>`); missing,
   duplicate and out-of-range assignments are typed errors.
3. `CharacterFactory.create(name, rolled, assignment, backgroundId)` seeds a character. Every
   catalog skill is present at level ≥ 1. A background only adds starting XP, kit, gold and a story
   tie; nothing restricts later training.

### Skills and XP
- `SkillCatalog` is the complete, fixed registry. Unknown skills are rejected; profession entries
  (`playable = false`) exist but cannot be checked yet.
- Level is derived from stored XP (`XpCurve`); XP is never stored above the level-20 threshold.
- XP is never requested — it is a consequence of an `AttemptCheck`, decided by `XpAwardPolicy`:
  success pays the tier award, a meaningful failure pays a fraction, no-roll outcomes pay nothing.
- **Anti-repeat:** each check names a `ChallengeKey(subjectId, topic)`. The subject must be a real
  entity (location, NPC, encounter, creature instance). Each `(skill, subject, topic)` pays at most the
  success award in total, so retrying an unchanged obstacle pays nothing, and fail-then-succeed tops
  up to the success amount. A skill can earn from at most N topics per subject, which limits farming
  by rephrasing. **The orchestrator composes keys, not the narrator.** When the obstacle really
  changes (the guard is now suspicious), the orchestrator uses a new topic.

### Checks
`CheckEngine.prepare` validates the request and fixes everything before any roll: attribute
modifier, skill bonus (`level / 4`, so level 20 gives +5, never +20), the equipment bonus (taken from
inventory, never from the request), circumstance modifiers (each and in total bounded; out-of-bounds
values are **rejected, not clamped**), the DC from the tier, and unmet prerequisites.
`PreparedCheck` has no API that changes it. `resolve` then:

| Situation | Outcome | Die rolled |
| --- | --- | --- |
| tier `IMPOSSIBLE`, or a prerequisite is unmet | `IMPOSSIBLE` | no |
| tier `TRIVIAL` | `AUTOMATIC_SUCCESS` | no |
| `1 + bonus ≥ DC` (cannot fail) | `AUTOMATIC_SUCCESS` | no |
| `20 + bonus < DC` (cannot succeed) | `IMPOSSIBLE` | no |
| otherwise | `SUCCESS` if `d20 + bonus ≥ DC`, else `FAILURE` | yes |

Every attempt, including non-rolls, produces a `RollRecord` with the die, every modifier, the DC,
the total, the outcome, an explanation, `importance` (`IMPORTANT` = announce aloud, `ROUTINE` =
silent), and the action id. It is stored inside the `CheckResolved` event; `Campaign.rolls` is
the roll history the UI should show.

Natural-1/natural-20 special cases are **not** implemented. That rule is not decided yet.

### Actions and exactly-once
`ActionProcessor.submit(campaignId, command)` loads the campaign, runs `Rules.process`, then
atomically saves the new campaign, and returns one of:

- `Committed(events)`: applied and durably saved.
- `Replayed(events)`: this action id was already applied. Nothing changes and the **original**
  events come back, so a replayed narration line re-reads results instead of re-running them.
- `Rejected(errors)`: refused. Nothing changes and the action id is not consumed.
- `Unavailable(reason)`: the save is missing or unreadable. It is never overwritten.

The idempotency receipt (`AppliedAction`) is written in the **same atomic file replace** as the
change and its events. A reused action id with a different payload (for example the same attempt
at an easier difficulty) is refused with `ActionIdConflict`, because each receipt pins a SHA-256 of the
canonical command.

Dice for an action come from `SplitMix64(seed, action ordinal, hash(action id))`. A retry after
a crash before commit therefore produces the same roll, so killing the app cannot reroll a bad result.

Commands in this slice: `AttemptCheck`, `Travel`, `SpendGold`, `StartEncounter`,
`CommitWorldProposal`. There is deliberately no command that grants anything.

### Persistence
`FileCampaignStore(dir)` stores one JSON document per campaign. A save writes `<id>.json.tmp`,
fsyncs it, then does an atomic rename over `<id>.json`. After a crash, load returns either the old
campaign or the new one in full. A leftover `.tmp` file is ignored. On Android, use
`File(context.filesDir, "campaigns")`.

Schema strategy: every document carries `"schema": 1`. `CampaignCodec.decode` upgrades older
documents through `CampaignCodec.migrations` (one step per version). It refuses newer documents
with `UnsupportedSchema` instead of guessing, and reports a truncated, hand-edited or
inconsistent file (event-log gaps, dangling location) as `Corrupt`.

Why a file instead of Room/SQLite: an M1 campaign is small (a few hundred events). Replacing a
single file gives all-or-nothing commits with no schema or migration machinery, and it is pure JVM,
so it is tested against a real filesystem here. If history grows, add a Room-backed
`CampaignStore`; `ActionProcessor` and the rules do not change.

### World proposals (AI → world)
1. **Parse** with `WorldProposalParser.parse(rawModelText)`. The parser is strict: malformed JSON,
   wrong types, missing fields, unknown location kinds and **any unknown field** (`gold`, `xp`,
   `loot`, …) are typed errors.
2. **Validate** with `WorldProposalValidator`. It checks keys, text budgets, counts, that
   references resolve to proposed keys or existing ids, and that creatures exist in the catalog.
   An initial region also needs a start location, a settlement or inn, a wilderness, a dungeon, at
   least 2 NPCs, and every location reachable from the start.
3. **Identify** with `WorldCommitter`. It replaces proposal keys with engine ids (`loc-0001`,
   `npc-0005`, `fact-0007`) from a monotonic counter. Keys cannot contain `-`, so a proposal can
   never name or overwrite an existing id.
4. **Commit** through `Rules.newCampaign` (initial region) or the `CommitWorldProposal` command.
   Both are atomic and idempotent, and both record a `WorldContentAccepted` event.

Proposals only **add** content. Existing locations gain connections, but facts and NPCs are never
edited. `FallbackRegion.proposal` is the hand-written region to use when generation fails. It goes
through the same validator. New creature or item **templates** from the AI are not accepted yet;
locations may only reference existing catalog creatures.

### Encounters (data model only)
`StartEncounter` takes creatures that are native to the current location (`Location.creatures`)
and creates instances at template HP. It sets the turn to the player with
`awaitingPlayerDecision = true`. Nothing advances until the next typed command, so silence never
acts. `Travel` and a second `StartEncounter` are refused while an encounter is active. Attack,
defend, spells, MP, damage, loot rolls and defeat belong to the next slice.

## Provisional balance constants (need playtest sign-off)

All of these live in `Balance.kt` (plus the starter content in `Catalog.kt` and
`CharacterCreation.kt`) and can be changed without touching rule code.

| Constant | Default | Where |
| --- | --- | --- |
| Poor-roll safeguard | reroll unless total ≥ 66 **and** highest ≥ 14; max 20 rerolls, then keep best | `AttributeSafeguard` |
| Attribute modifier | `floor((score − 10) / 2)` | `attributeModifier` |
| Attribute ceiling | 20 (rolled max 18; headroom for story gains) | `AttributeScores.MAX_SCORE` |
| XP curve | level L at `10·(L−1)·L/2` (L2 = 10, L5 = 100, L10 = 450, L20 = 1900) | `XpCurve` |
| Skill bonus | `level / 4` (0 … +5) | `CheckRules` |
| Modifier bounds | each ±5; equipment total ±3; circumstance total ±5 | `CheckRules` |
| DCs | Easy 8, Moderate 12, Hard 15, Very hard 18, Heroic 21 | `CheckRules` |
| Success XP | Easy 4, Moderate 8, Hard 12, Very hard 16, Heroic 20 | `XpRewards` |
| Failure XP | 50 % of success (min 1) | `XpRewards` |
| Topics per subject per skill | 3 | `XpRewards` |
| Starting HP / MP | HP `10 + 2·CON mod` (min 4); MP `4 + 2·INT mod` (min 2) | `VitalsFormula` |
| Creature XP | `5 · level · {minion 1, standard 2, elite 4}` (defined, not yet used) | `CreatureXp` |
| World proposal budgets | ≤ 8 locations, ≤ 6 NPCs, ≤ 12 facts, ≤ 3 creatures per location, ≥ 2 initial NPCs; name 60 / description 600 / fact 300 chars | `WorldLimits` |
| Max enemies per encounter | 4 | `BalanceConfig` |
| Initiative | player acts first | `Rules.startEncounter` |
| Skill roster | 14 playable + 3 future professions | `SkillCatalog` |
| Backgrounds | Caravan Guard, Hedge Apprentice, Street Runner, Wandering Minstrel; 3 skills at 10 XP, 15 gold, kit each | `Backgrounds` |
| Items and creatures | 9 items, goblin and giant rat, with stats and loot % | `ContentCatalog.STARTER` |

**Locked, not tunable here:** six attributes, 4d6-drop-lowest with player assignment, a classless
and nonrestrictive background, a fixed skill catalog, independent skill levels 1–20 with no character
level, d20 checks with bounded modifiers, a difficulty fixed before the roll, failure XP without
farming, and engine-owned outcomes.

## Next-slice integration guidance (Android/audio)

- **Wiring:** add `implementation(project(":rpg"))` to `app/build.gradle.kts`. org.json is
  `compileOnly` in `:rpg`, so the app uses Android's platform copy. Create one `ActionProcessor` over
  `FileCampaignStore(File(filesDir, "campaigns"))` and keep it in the service. Its methods are
  synchronized and do disk I/O, so call them off the main thread.
- **Action ids:** mint an `ActionId` once, when an interpreted intention is accepted, **before**
  narrating anything about it. Persist it alongside the narration checkpoint. After a pause,
  resume or restart, resubmit the same id: you get `Replayed` with the original events. Narrate
  from events and never re-run the rules to narrate.
- **Narration order:** submit → `Committed`/`Replayed` → speak lines generated from those events.
  Per the M0 contract, a cough during a line pauses speech only; it never resubmits anything.
- **Announce rolls** whose `RollRecord.importance == IMPORTANT`. Show all of `Campaign.rolls` in
  the companion UI.
- **AI adapter:** run model output through `WorldProposalParser.parse` and submit it as
  `CommitWorldProposal` (or `createCampaign` for the first region). On `Invalid`, retry a bounded
  number of times, then use `FallbackRegion.proposal`. Intent interpretation should produce a
  `CheckRequest`. The **orchestrator** chooses the `ChallengeKey` from engine ids, and the engine
  validates everything else.
- **Combat next:** add attack/defend/cast/use/retreat commands that act on `Encounter`. Use
  `CreatureTemplate` stats and loot tables, and `CreatureXp` for rewards. Spend MP from `Vitals`.
  Keep `awaitingPlayerDecision` true between turns.

## Open design questions

1. Safeguard thresholds, XP curve, DCs and XP amounts (table above).
2. Natural 1 / natural 20: automatic results or not?
3. Should a background grant starting XP (current) or flat starting levels? Is the roster right?
4. Anti-repeat granularity: are topics-per-subject (3) plus orchestrator-owned topics enough, or
   should challenges reset on a new day?
5. Rare story-driven attribute increases: what event authorises them? No command exists yet.
6. Initiative and the turn order with several enemies.
7. MP formula: should MP scale with INT (current), WIS, or the Magic skill?
8. File store vs Room: revisit if a campaign's event log grows past a few thousand events.
