# Contributing to Umm

Thanks for your interest in improving Umm. Bug reports, fixes, and small features are welcome. For anything
larger than a bug fix, open an issue first so we can agree on the approach before you spend time on it.

## Reporting a bug

Open an issue with:

- your phone model and Android version
- the Umm version (Settings → About)
- what you said or did, what you expected, and what happened
- the app you were typing into, if it matters

Please don't paste your OpenRouter key, even a revoked one. For privacy or security problems, email
[adurs2002@gmail.com](mailto:adurs2002@gmail.com) instead of opening a public issue.

## Development setup

You need JDK 17, the Android SDK (compile SDK 37), and a phone or emulator running Android 10 or newer.

1. Clone the repo and put an OpenRouter key in `.env` at the repo root:

   ```
   OPENROUTER_API_KEY="sk-or-..."
   ```

   The file is git-ignored. Only debug builds read it, so the debug app starts already connected. Debug APKs
   therefore contain your key; don't share them.

2. Build and install:

   ```bash
   ./gradlew installDebug
   ```

## Project layout

- `core/` is an Android library with the whole dictation pipeline and no UI: recording and silence detection,
  the OpenRouter client, cleanup prompts, model and level policy, storage, and usage stats.
- `app/` holds the keyboard service (`ime/`) and the app's screens (`settings/`).
- `models/recommended.json` lists the default models. The app fetches it from `main` once a day, so a change
  there reaches every user without a new release.
- `docs/superpowers/` has the original design spec and implementation plan.

## Tests

```bash
./gradlew testDebugUnitTest
```

Write the test first for anything in `core/` and for the keyboard's pure helpers in `app/src/test`. The suite
runs on the JVM with Robolectric; it needs no phone.

Live tests call the real OpenRouter API and cost a fraction of a cent per run. They only run when asked:

```bash
./gradlew :core:testDebugUnitTest -Plive --tests '*LiveOpenRouterTest*'
```

Release builds are shrunk with R8. To check that a change survives shrinking, build the `staging` variant. It
is the release code under its own app id, so it installs next to your debug build:

```bash
./gradlew installStaging
```

For UI changes, include before and after screenshots in the pull request.

## Code style

- Match the surrounding code: naming, comment density, and structure.
- Keep files focused on one job. `core/` must not depend on anything in `app/`.
- Never log, print, or send the API key anywhere except `openrouter.ai`.
- No analytics or tracking.

## Commits and pull requests

Commits follow [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): description`, in the
imperative, subject under 50 characters, with a body that explains why. One logical change per commit.

CI runs the unit tests and builds an APK on every pull request. It must pass before merge.

## Releases

Maintainers publish releases by pushing a tag such as `v1.2.3`. The release workflow builds, signs, and attaches
the APK to a GitHub Release. Tags with a suffix, such as `v1.2.3-beta.1`, become pre-releases.

## License of contributions

Umm is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). By submitting a contribution, you
agree that:

1. your contribution is licensed to everyone under the same terms, and
2. you also grant Aadarsha Gopala Reddy a perpetual, worldwide, royalty-free license to use, modify, and
   relicense your contribution, including for commercial purposes, so the project can keep a single owner.

You keep the copyright in your contribution.
