# Umm

A voice keyboard for Android. Switch to it in any text field, talk, and it types a cleaned-up version of what you said.
Named after the filler word it deletes.

Umm uses [OpenRouter](https://openrouter.ai) for speech-to-text and cleanup, with each user's own OpenRouter account.
There is no Umm server and no Umm bill: audio goes from the phone to OpenRouter and nowhere else.

## What it does

- **Voice-only keyboard.** Pick Umm from the keyboard switcher; it starts listening right away and stops after a
  few seconds of silence. The cleaned text goes into the field, and Umm switches back to your normal keyboard.
- **Continuous mode.** Double-tap the mic to keep recording through pauses (for whispering or thinking out loud)
  until you tap **Finish**.
- **Cleanup levels.** Raw, Light (removes filler and self-corrections), Formatted (adds lists and paragraphs), and
  Polished (rewrites into clear prose).
- **Per-app categories.** Email, Messaging, Social, and Notes each have their own level, seeded with popular apps.
  Unknown apps can be assigned from the keyboard.
- **Mixed-language speech.** Hinglish and similar speech is kept as spoken, never translated, in Latin or native
  script per category.
- **Models that update themselves.** The recommended models live in [`models/recommended.json`](models/recommended.json)
  and are fetched daily, so the default can change without an app update. Users can also follow the newest
  speech-to-text model or pick their own.
- **Nothing lost.** Failed dictations keep their audio for retry (deleted after 7 days). If the field is gone by
  the time text is ready, it goes to the clipboard. The last 50 dictations are in History.

## How a dictation works

1. The keyboard records mono AAC (16 kHz, 32 kbps) and watches the mic level to detect speech and silence on the
   phone. Silent recordings are never uploaded.
2. The audio goes to OpenRouter's `/audio/transcriptions` endpoint (currently `openai/gpt-4o-mini-transcribe`,
   falling back to `mistralai/voxtral-mini-transcribe`).
3. Unless the level is Raw, the transcript goes to `/chat/completions` with the level's instructions (currently
   `google/gemini-3.5-flash-lite`, falling back to `deepseek/deepseek-v4.1-flash`). The transcript is treated as
   data, so dictating "ignore previous instructions" gets cleaned, not obeyed.
4. The text is inserted into the field the dictation started in.

A short dictation typically takes 1.2–1.8 s from the end of speech to text on screen.

## Building

Requirements: JDK 17 and the Android SDK (compile SDK 37; the Gradle plugin downloads it if the licenses are
accepted).

```bash
./gradlew assembleDebug
```

For development, put your OpenRouter key in a `.env` file at the repo root. It is git-ignored, and only debug
builds read it, so the debug app starts already connected:

```
OPENROUTER_API_KEY="sk-or-..."
```

Debug APKs therefore contain your key. Don't share them. Release builds never read `.env`.

Install on a phone over wireless debugging:

```bash
adb pair <ip:pairing-port> <code>
adb connect <ip:port>
./gradlew installDebug
```

## Tests

```bash
./gradlew testDebugUnitTest
```

JVM and Robolectric tests cover the pipeline, silence detection (including real mic traces from a Pixel 9 Pro),
model selection, storage, and the keyboard's insertion rules.

Live tests call the real OpenRouter API with the spoken fixtures in `core/src/test/resources/audio` and cost a
fraction of a cent per run. They only run when asked:

```bash
./gradlew :core:testDebugUnitTest -Plive --tests '*LiveOpenRouterTest*'
```

Override the models under test with `-PsttModel=...` and `-PcleanupModel=...`. `scripts/make-fixtures.sh`
regenerates the fixture clips.

CI (`.github/workflows/ci.yml`) runs the unit tests and builds a debug APK on every push to `main` and every pull
request. Run it manually with **live** checked to include the live tests; that needs an `OPENROUTER_API_KEY`
repository secret.

## Project layout

- `core/`: the Android library with the whole dictation pipeline and no UI: recording and silence detection,
  the OpenRouter client, cleanup prompts, model and level policy, and storage. A future floating-bubble front end
  can reuse it unchanged.
- `app/`: the keyboard service (`ime/`) and the onboarding, settings, categories, models, and history screens
  (`settings/`).
- `models/recommended.json`: the default models, also bundled into the APK.
- `docs/superpowers/specs/` and `docs/superpowers/plans/`: the v1 design and implementation plan.
- `docs/testing/device-checklist.md`: the manual on-device checks.

## Status

v1 works end to end on a Pixel 9 Pro (Android 17). Still open:

- Several items in the [device checklist](docs/testing/device-checklist.md) have not been walked through yet,
  including the "Connect with OpenRouter" sign-in callback (`umm://oauth`). Pasting a key works either way.
- `models/recommended.json` is fetched from this repository's raw URL, which only works once the repository is
  public. Until then the app uses its bundled copy.
- Before a Play Store release: a privacy policy, the Data safety form, and a release signing setup.
- Planned later: a floating-bubble front end, custom categories.
