# Handoff: main

Last updated: 2026-11-17. Branch: `main` at `cd04a844`. Replaces the old `feature/addons-client` handoff.

## Done
- **Upstream sync complete (stages 1-3):** Gambit is now on OwnTV v5.2.2 (`42057337`), core 1.0.71, and CI builds with JDK 25 (Gradle 9.8.2).
  - **Stage 3 merged:** v5.2.1 -> v5.2.2 (203 commits), core 1.0.69 -> 1.0.71. Branch `sync/stage-3` (kept): merge `e34f6a0c`, then `1ade9b84` (Gambit CI on JDK 25, its own commit).
  - Merged to main with `--no-ff` in `cd04a844`. CI on `cd04a844`: Gambit CI, Android CI and i18n all passed.
  - Shield: CI run 44125753235 debug APK (`1ade9b84`) installed with `adb install -r` (signer matched). Shield checks 1-4 and 6 passed (rail open/close on Home, Live and Movies; player Left panels and folder pick; More highlight; Home names; reboot lands on the launcher). No Gambit crash or ANR in logcat. Report: C:\Users\darre\reviews\gambit-stage3-shield-checks.md.
- **Upstream sync stage 2 merged:** v5.2.1 -> `d1f12648` (guide performance, Coil cache, Search backdrop, Home row focus). Core stays 1.0.69; no Gradle or JDK change.
  - Branch `sync/stage-2` (kept): merge `5f1a3e56` (1 conflict in `EpgScreen.kt`; `GambitGuide.kt` ruler and programme strip take `scrollPx: () -> Int`).
  - Merged to main with `--no-ff` in `ff6d411a`. CI on `ff6d411a`: Gambit CI #40, Android CI #17 and i18n #20 all passed.
  - Shield: CI #39 debug APK installed with `adb install -r` (signer matched), launched with no crash logged. Darren passed checks 1-4 (guide scrolling and alignment, day label, Home rows, Search backdrop). Check 5 (setup choice cards) skipped: only reachable in the first-run wizard.
- **Upstream sync stage 1 merged:** OwnTV v5.1.0 -> v5.2.1 (`8f47da68`), core 1.0.64 -> 1.0.69.
  - Branch `sync/stage-1` (kept): merge `65720c5e` (9 conflicts resolved, six post-merge fixes, ProviderTags decode moved to `gambit/text/GambitProviderTags.kt`), then `e764d418` (compile fixes for core 1.0.69: `shownColor` in `LiveStage.kt`, new `gambit_info_history` string).
  - Merged to main with `--no-ff` in `2e880626`. CI on `2e880626`: Gambit CI #38, Android CI #16 and i18n #19 all passed.
  - Shield: CI #37 debug APK installed with `adb install -r` (signer matched), launched with no crash logged; Darren passed all 7 on-screen checks (guide ▶ and preview on OK, move overlay colours, decoded names, recently watched panel, info bar tiles, long-press popup guard, player Left folder list).
- CLAUDE.md now covers repo identity, working rules and CI notes (`4b515054`, merged in `c1e3592a`).
- Trakt core merged into main (`f4c1454f`), in package `tv.own.owntv.gambit.trakt`:
  - device-code sign-in, token refresh, scrobble start/pause/stop, sync (history, playback, watchlist);
  - typed errors (`TraktError`); `description` is for logs only (KDoc + CLAUDE.md rule);
  - 147 technical literals classified in `tools/i18n/safe_literals.txt` (`d8031d79`);
  - unit tests with fixture JSON and fake hosts only.
- CI on `f4c1454f`: Gambit CI #34, Android CI #14 and i18n #17 all passed.
- Shield (192.168.50.101:5555): CI #34 debug APK installed with `adb install -r` (signer matched the installed app). The app launches, no crash was logged, and the Movies page drew normally (rail, posters, clock). Trakt itself was not exercised: there is no UI for it yet.

## Open
1. **Trakt has no UI, no Koin wiring and no token storage.** Next action: plan the wiring (`TraktClient(config, tokenStore, httpClient)`, a `TraktTokenStore` implementation, a sign-in screen) and get approval before editing.
2. **Trakt keys are not supplied yet.** The plan was local.properties -> BuildConfig -> `TraktConfig`. That touches Gradle, so it needs Darren's OK first.
3. **When the Trakt UI is built:** map each `TraktError` type to a `gambit_strings.xml` entry; never show `description`.
4. **Trakt calls not proven by fixtures** (check against the live API once wired):
   - `GET /sync/history` without a type;
   - watchlist path `sync/watchlist/{type}/rank/asc`;
   - scrobbling an episode by show + season + number (`TraktScrobbleItem.EpisodeOfShow`).
5. **Unmerged branches, status not checked:** `feature/addons-install` (WIP), `test/fixes-combined`, `claude/android-tv-family-setup-l2njf4`. Next action: review each and decide merge or leave.
6. **Stage 3 check 5, not done:** with a locked category, confirm it is hidden in the player's folder list (Left, Left). Darren to do this on the Shield; it was skipped because it needs a category lock or PIN.

## Compiled and tested in CI, never run on a device
- All Trakt code (there is no UI to reach it yet).

## Waiting on Darren
- How Trakt keys are supplied (Gradle change, item 2).
- What to do with the three unmerged branches (item 5).
- Stage 3 check 5 on the Shield (item 6).

## Notes
- Commit `ac1c8340` (Trakt models) does not compile its tests on its own; the merged result is fine. Only matters for bisecting.
- Trakt marks `client_secret` as deprecated for on-device apps; `TraktConfig.clientSecret` is optional and only sent when set.
