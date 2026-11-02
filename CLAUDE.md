# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Gambit

## What this is
- Fork of the OwnTV Android TV app.
- Goal: TiviMate-style look and feel, plus a Nuvio/Stremio-style element.

## How to work
- For any change touching more than one file, propose a plan and wait for approval before editing.
- One feature or fix per task. Keep changes small.

## Build and verify
- Build command: `./gradlew assembleDebug`
- A task is not done until the build passes.
- If the build fails, report the exact error. Do not guess at a fix silently.

## End of every task
Give a short summary:
- Files changed
- Build result (pass/fail)
- Anything not done or uncertain

## Rules learned
<!-- Add one-line rules here when Claude makes a mistake -->

# Upstream OwnTV reference

## Upstream app

OwnTV: an **Android TV** IPTV *player* (Kotlin, Jetpack Compose for TV, Koin, Room). It is player-only —
users bring their own Xtream / M3U / Stalker sources; never add bundled channels or content. Navigation
must stay D-pad / remote-first. `applicationId tv.own.owntv`, minSdk 26, targetSdk 36, compileSdk 37.

## Commands

```bash
./gradlew assembleDebug                         # build (APK in app/build/outputs/apk/)
./gradlew testStandardDebugUnitTest             # JVM unit tests (what CI runs)
./gradlew testStandardDebugUnitTest --tests "tv.own.owntv.player.SubtitleStyleTest"   # one test class
./gradlew lintStandardDebug                     # lint (abortOnError = true; CI gate)
./gradlew :app:generateBaselineProfile          # re-record baseline profile (needs API 33+ x86_64 emulator)
```

- Product flavors split by ABI: `standard` (arm64-v8a + armeabi-v7a, real TV boxes) and `x86_64`
  (emulators). CI tests and lints only the `standardDebug` variant.
- `preBuild` depends on `verifyI18nLiterals`, which runs `tools/i18n/check_hardcoded_strings.py`.
  **A hardcoded user-visible string in Kotlin fails the build.** The fix is to move it into
  `strings_*.xml` or classify it as technical (see `tools/i18n/README.md`). There is no "accept" flag.
- Other i18n checks CI runs (`.github/workflows/i18n.yml`):
  ```bash
  python3 tools/i18n/check_hardcoded_strings.py verify --bootstrap
  python3 tools/i18n/check_number_locale.py
  python3 tools/i18n/check_text_overflow.py
  ```
- `.envrc` sets `GRADLE_USER_HOME=$PWD/.gradle-cache` and derives `JAVA_HOME`. CI uses JDK 21.

## Architecture: two repositories

This repo is **only the TV UI layer**. Database, sync, parsers (M3U/Xtream/Stalker), EPG, backup,
settings storage, repositories, the playback engines and **all translated strings** live in the
separate OwnTV_Core repo, consumed as Maven artifacts `tv.own.owntv:core` and `tv.own.owntv:player-core`.

- Version pin: `owntvCore` in `gradle/libs.versions.toml`. Packages like `tv.own.owntv.core.content`,
  `tv.own.owntv.core.live`, `tv.own.owntv.core.home` are imported from that artifact, not from this repo
  (the only local `core/` package is `core/i18n`).
- To develop against Core source, set `owntv.corePath=<path>` in `~/.gradle/gradle.properties`
  (never in the repo); `settings.gradle.kts` then does an `includeBuild` substitution. CI always uses
  the pinned artifact.
- `tools/i18n/locales.json` is a **copy** of Core's file (Gradle needs it at configure time to build
  `localeFilters`). Change it in Core first, then copy. CI's `verify-pin` job checks they match on
  `bump/core-*` branches; those bump PRs skip the test/lint/i18n jobs.
- Some docs (`docs/i18n.md`) mention scripts like `validate_strings.py` / `gen_supported_locales.py`
  that now live in Core, not here.

## Architecture: app module (`app/src/main/java/tv/own/owntv/`)

- `MainActivity.kt` / `OwnTVApp.kt` — entry points.
- `di/` — Koin: `AppModule.kt` (ViewModels and readers), `PlayerModule.kt`.
- `features/<area>/` — one package per screen area, usually `XScreen.kt` + `XViewModel.kt`.
  `features/shell/` hosts the top-level shell (`OwnTVShell.kt`), the rail, and shared overlays.
- `player/` — the TV player HUD, surfaces, mini-player. It *drives* the engines from `player-core`
  (libmpv + ExoPlayer behind one `PlaybackEngine` interface with a fallback ladder); it does not
  implement them.
- `ui/stage/` + `ui/theme/StageTokens.kt` — the "Stage" design language used by newer screens
  (`LiveStage`, `GuideStage`, `SettingsStage`, `VodStage`, `StageRail`).
- `ui/components/`, `ui/theme/` — reusable components and theming (accent, glass effect, fonts).

## i18n rules (enforced)

- Only the presentation layer resolves strings. ViewModels, repositories and Workers emit typed
  semantic state; Compose words it via `stringResource` / `pluralStringResource`. Exceptions are a
  small named set of final renderers listed in `docs/i18n.md`.
- Strings used for comparison/parsing/protocol are keys, not display labels — never translate in place.
- Counted sentences must use plurals (`PluralsCandidate` is a fatal lint).
- String files are split by area (`strings_setup.xml`, `strings_settings.xml`, `strings_player.xml`,
  `strings_content.xml`, `strings_features.xml`); key naming is `<area>_<screen>_<element>[_<variant>]`.
  Full guide: `docs/i18n.md`; deep design: `docs/internationalization.md`.

## Build quirks

- No `kotlin-android` plugin: Kotlin comes from AGP 9's built-in support. Keep the Compose compiler
  plugin on exactly the `kotlin` version in `libs.versions.toml`; KSP versions independently.
- Local/debug builds get `versionName 99.99.99` / `versionCode 99999` so they install over releases;
  CI injects the real version from `v*` tags.
- Release builds use R8 (`isMinifyEnabled` + `isShrinkResources`); keep rules in `app/proguard-rules.pro`.
- Optional Gradle props: `owntv.devTools=true` (maintainer tools), `diagnosticBuild=true`.
- Unit tests run with `unitTests.isReturnDefaultValues = true` (android.util.Log etc. are no-ops).

## Contribution conventions

- Commit messages become release notes: imperative, reader-facing ("Fix EPG grid scroll desync on D-pad").
  `[skip ci]` for doc/config-only commits.
- Room migrations must stay additive (no user data wiped).
