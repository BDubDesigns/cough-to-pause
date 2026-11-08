# AI dungeon master (optional)

Andy narrates from the engine's own facts by default. A model can rephrase those facts into better
prose, and nothing else changes: the model never rolls dice, never picks a difficulty, never grants
XP, gold, items or HP, and never invents world facts.

Part of #1. Tracks #6.

## Turn it on

1. Create an API key with your provider. DeepSeek is the default because it is cheap; any
   OpenAI-compatible endpoint works.
2. In the app: **Adventure → AI dungeon master**.
3. Leave the defaults or change them:
   - **Base URL** `https://api.deepseek.com/v1` (the code appends `/chat/completions`)
   - **Model** `deepseek-flash`
4. Paste the key, tap **Save**, then **Test**. A reply in the status line means the round trip works.
5. Play. The provenance line at the top of the Adventure screen shows which narrator spoke:
   `Andy · deepseek-flash via api.deepseek.com` or `Andy · scripted narration`.

Clear the key field and save to go back to scripted narration.

## What is sent

One small request per narrated beat: the player's name, background, HP and gold, the current
location and its description, the NPCs present, the exits, and the engine's factual results for that
beat (rolls, XP, travel, spending). Plus the system prompt that forbids inventing mechanics.

Not sent: microphone audio, the event log, saved campaigns, or anything from the M0 harness.

## Where the key lives

- Stored with `EncryptedSharedPreferences` (AES-256-GCM, key material in the Android Keystore).
- Never compiled into the APK, never committed, never written to logs or the transcript.
- The status string shows the model and host only, never the key.
- `AiConfig` and `PromptBuilder` are unit tested to prove the key stays out of the request body and
  out of the displayed status.

## Cost

One request per beat, ~220 output tokens, a few hundred input tokens. A 20-minute session with
forty beats is a few cents on DeepSeek. The app does not batch, retry in a loop, or call the model
for repeated or deterministic content.

## When it fails

Any failure — no key, HTTP error, timeout, unreadable body, empty prose — falls back to the
deterministic narrator for that beat, adds one line to the journal
(`(AI unavailable: HTTP 401 — scripted narration)`), and shows `last error` in the settings card.
The game never blocks on the network, and offline play is unchanged.

## Privacy and permissions

`INTERNET` is the only permission this feature adds. The app makes no request until a key is saved,
and only to the base URL you entered. The harness, the cough detector and the narration pipeline are
unchanged: they still never touch the network.

## What is still missing

- World **generation** (a second campaign still uses the validated fallback region) — #6.
- Model-proposed **intent** parsing; the keyword interpreter still maps text to commands — #6.
- Per-turn latency measurement and battery impact — #11.
- Wake word and the contextual response window — #8.
