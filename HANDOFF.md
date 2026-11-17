# Handoff: main

Last updated: 2026-11-17. Branch: `main` at `f4c1454f`. Replaces the old `feature/addons-client` handoff.

## Done
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
6. Upstream OwnTV sync: not started. Plan is in C:\Users\darre\reviews\gambit-upstream-sync-plan.md (3 stages). Blocked on Darren's decision: upstream commit 89f62ca0 needs JDK 25, gambit-ci.yml pins 21.

## Compiled and tested in CI, never run on a device
- All Trakt code (there is no UI to reach it yet).

## Waiting on Darren
- How Trakt keys are supplied (Gradle change, item 2).
- What to do with the three unmerged branches (item 5).

## Notes
- Commit `ac1c8340` (Trakt models) does not compile its tests on its own; the merged result is fine. Only matters for bisecting.
- Trakt marks `client_secret` as deprecated for on-device apps; `TraktConfig.clientSecret` is optional and only sent when set.
