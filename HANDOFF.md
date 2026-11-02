# Handoff: Trakt core (`feature/trakt-core`)

Last updated: 2026-11-02

## Status: blocked, waiting on one decision

The Trakt core (no UI) is written, tested and pushed. The build fails only on the repo's
hardcoded-text check, and fixing that means editing an existing file, which needs approval.

## Done

- New package `tv.own.owntv.gambit.trakt` (app/src/main/java/tv/own/owntv/gambit/trakt/):
  device-code sign-in, `TraktTokenStore` interface, automatic token refresh, scrobble
  start/pause/stop, sync (history, playback progress, watchlist read/add/remove), typed errors.
- 49 unit tests with fixture JSON and a fake host (app/src/test/.../gambit/trakt/,
  app/src/test/resources/trakt/). No live calls, only fake keys.
- Only new files added. No existing OwnTV file changed.
- Last run (2026-10-21, with only `verifyI18nLiterals` skipped): `:app:testDebugUnitTest`
  226 tests, 0 failures; `:app:assembleDebug` passed.

## Open

1. **Blocker:** `:app:verifyI18nLiterals FAILED — Hardcoded text found. Every literal must be
   reachable by translators or declared technical.`
   - Fix: register the Trakt literals in `tools/i18n/safe_literals.txt` with the repo's own
     command, e.g. `python3 tools/i18n/check_hardcoded_strings.py classify-safe --path <file> --text <literal> --category json|url|log`.
   - Needs the owner's OK, because the rule was "don't edit existing OwnTV files without asking".
2. Re-run the full build with no checks skipped: `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
3. Optional: commit `ac1c834`'s tests don't compile alone (they use `TraktClient` from `f3aa79e`).
   The branch tip is fine. Reorder only if bisecting matters.
4. Write the final "complete" report (exit criteria, plug-in interfaces, key supply).

## Not verified (fixtures can't prove these)

- `GET /sync/history` without a type.
- Watchlist path `sync/watchlist/{type}/rank/asc`.
- Scrobbling an episode by show + season + number (`TraktScrobbleItem.EpisodeOfShow`), taken from Trakt's legacy docs.

## Notes for whoever picks this up

- Trakt now marks `client_secret` as deprecated for on-device apps. `TraktConfig.clientSecret`
  is optional and only sent when set.
- Entry point for later wiring: `TraktClient(config, tokenStore, httpClient)` exposes
  `session`, `deviceAuth`, `scrobbler`, `sync`. Storage plugs in via `TraktTokenStore`.
- Keys are never hard-coded. They're meant to come from local.properties → BuildConfig in a
  separate step, then be passed to `TraktConfig`.
- Out of scope, untouched: UI, Koin wiring, storage implementation, Gradle/BuildConfig.
