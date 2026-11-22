# Handoff: main

Last updated: 2026-11-22. Branch: `main` at `c9b1c012`. Replaces the old `feature/addons-client` handoff.

## Done
- **Addon playback fixes done.** Branch `fix/addon-playback` (kept), commit `ca49c747`, merged to main with `--no-ff` in `c9b1c012`. Gambit files only, no upstream edits.
  - **Stuck plays end in an error, not a spinner** (`gambit/playback/AddonPlayWatch.kt`, `GambitAddonPlayer.kt`): after 20 s of buffering with no progress and no error, the same stream is played again through the same proxy session with `vodEngineOverride = EXO_ONLY`; Core then shows its own "Playback failed on both video engines" screen. If the ExoPlayer retry is stuck for 60 s, Gambit stops the player and the stream list says "Couldn't play this stream on this TV". The proxy session now survives a 5 s gap with no address during a player reset.
  - **"May not play on this TV" tag** (`StreamRows.likelyDvProfile7` in `StreamBoard.kt`): REMUX + DV (or an explicit P7/FEL/DV MEL), not WEB, Movies Anywhere or P5/P8. Tagged rows move below the other streams of the same quality; nothing is hidden. Tests: `StreamRowsDvTest`, `AddonPlayWatchTest`.
  - Diagnosis behind it (Shield, The End of Oak Street): a dual-layer Dolby Vision Profile 7 Blu-ray REMUX (4K HEVC Main 10 PQ base layer plus a 1080p enhancement-layer video track). mpv opened the second track, lost the surface (`configure failed with err 0xffffffea`); ExoPlayer selected no video track (`format=null`). The Shield lists no Dolby Vision decoder. The Odyssey 98 GB (Movies Anywhere hybrid, single track) plays on mpv in hardware. The proxy was not the cause.
  - CI on `c9b1c012`: i18n #25 and Android CI #22 passed; Gambit CI #64 (45082923141) failed on the flaky `HomeRowBoardTest` (Open 0e) and passed on re-run (attempt 2). Branch run 45071172117 failed once on the flaky `GambitStreamProxyTest` port check (Open 3) and passed on re-run.
  - Shield: CI run 45071172117 APK (`ca49c747`) installed with `adb install -r`. Darren's checks: 2-9 passed; 3/4 ended in a clean "Playback failed on both video engines" error (audio only, no spinner). Check 1 (tag on Oak Street DV REMUXes) could not be tested: the addons no longer return any REMUX+DV stream for that title (all 50 rows read from the screen are plain-ASCII labels; the matcher is fine).
- **Home row in the Live sports slot done.** Branch `feature/home-row` (kept), commit `e899a123`, merged to main with `--no-ff` in `8b859829`.
  - The row shows "Discovery · <catalogue name>": up to 20 posters from the first Movies catalogue of the enabled addons, in addon order; if that one fails or is empty, the next is tried, 3 in all, else the row hides. Then a "See all" card. No new setting: reorder addons in More → Addons to change the feed.
  - A poster opens the same title page in Discovery (Back shows that catalogue's grid); "See all" opens Discovery on that catalogue. Gambit code in `gambit/discovery/HomeRowBoard.kt` and `gambit/ui/home/`.
  - One-time migration moved to key `home_live_sports_shown_v2` (`GambitHomeRows`): the row is shown again on every adult profile and stays hidden on kids profiles; the row never loads or draws on a kids profile. The v1 key is left as it was.
  - Upstream edits, all marked `// Gambit`, diff approved by Darren: `HomeScreen.kt` (row data, row, `onOpenDiscovery` parameter), `OwnTVShell.kt` (1 line), `HomeRowText.kt` (Settings names the row "Discovery"). `onOpenMatch` and `sportsVm` in `HomeScreen` are now unused but kept to keep the diff small.
  - CI on `8b859829`: Gambit CI #62, Android CI #21 and i18n #24 all passed.
  - Shield (192.168.50.101:5555): CI run 45064661775 APK (`e899a123`) installed with `adb install -r`; Darren passed on-screen checks 1-10.
- **Phase 2 done: addon content and playback.** Branch `feature/addons-board` (kept), merged to main with `--no-ff` in `5e15c34a`. Plan and decisions: C:\Users\darre\reviews\gambit-sports-addons-plan.md.
  - **Discovery in the Sports rail slot** (`30918611`): Type / Catalogue / Genre drop-downs over the addons' catalogues, poster grid with paging. Hidden on kids profiles. Upstream hooks marked `// Gambit` in `StageRail.kt` and `OwnTVShell.kt`; Sports' background score update untouched.
  - **Home "Live sports" row hidden by default** on every profile, existing and new (`3b8b6502`, `GambitHomeRows`; once per profile, remembered in Gambit's prefs so showing it again sticks).
  - **Title page** (`9526bb9e`): backdrop, logo or title, year, rating, runtime, genres, description; season picker and episodes for series. Details are asked first from the addon whose catalogue is showing.
  - **Stream list** (`e9090392`): every enabled stream addon in addon order; name and detail lines, quality, size, addon name; unplayable streams counted; per-addon errors by name and host only. Stream addresses and headers stay out of every `toString` (tested).
  - **Playback through a private localhost proxy** (`e4e8c838`, fixes `6872298d` and `b79179bd`): the player only gets a one-time `http://127.0.0.1:<port>/s/<token>/...` address. The proxy fetches the real stream with the addon's headers, passes Range through for seeking, follows redirects, refuses HLS with a message, carries the addon's subtitles, and closes when playback stops. 3 marked lines in `OwnTVShell.kt` (open the player; focus back on the same stream). Kids profiles are refused in the ViewModel.
  - Bug found on the Shield, fixed in `b79179bd`: on Android `InetAddress.getLoopbackAddress()` is `::1`, so the proxy binds to 127.0.0.1 by its literal address.
  - CI on `5e15c34a`: Gambit CI #60, Android CI #20 and i18n #23 all passed.
  - Shield (192.168.50.101:5555): Darren passed all Stage 2-5 checks, including a 98 GB 4K REMUX with seeking, Stream info showing only 127.0.0.1, and a clean error log after a network drop. Installed APK: CI run 45055959795 (`b79179bd`).
- **Trakt sign-in done (stages 1-4):** More → Trakt, next to Addons. Branch `feature/trakt-ui` (kept), merged to main with `--no-ff` in `51b2a7c3`. Plan and decisions: C:\Users\darre\reviews\gambit-trakt-ui-plan.md.
  - Client ID: GitHub secret `TRAKT_CLIENT_ID` -> env -> `BuildConfig.TRAKT_CLIENT_ID`, passed in `gambit-ci.yml` and `android.yml` (`check` and `build`), not `i18n.yml`. A blank or missing ID never crashes: `TraktConfig` is not built and the Trakt item is hidden. New Trakt app "Gambit" (Development type, PKCE, no client secret, redirect `urn:ietf:wg:oauth:2.0:oob`).
  - Tokens: Keystore-encrypted in `gambit_trakt` prefs, written with `commit()`. Koin module `gambitTraktModule` (one marked line in `OwnTVApp.kt`).
  - Sign-in page: QR code for `https://auth.trakt.tv/activate` (own ZXing code), the 8-character code in large type, address, countdown; states for waiting, signed in (Sign out), and each error, all from `gambit_strings.xml`. Hidden on kids profiles. Back cancels. Trakt's activate page does not pre-fill a `?code=` link, so the code is typed on the phone (tested 2026-11-17).
  - Renewal proven live: 7-day tokens with automatic renewal. `TraktRenewalCheck` passed on the Shield (forced, automatic before a call, and after a rejected token); the temporary button that ran it was removed in `94bb5095`. The check class and its tests stay. The Trakt page re-reads the saved sign-in each time it opens.
  - CI on `51b2a7c3`: Gambit CI #49, Android CI #19 and i18n #22 all passed; Android CI's `check` and `build` saw the secret (masked).
  - Shield (192.168.50.101:5555): Darren passed Stage 3 checks 1-6 and 9-11 (QR scanned from the sofa, code readable; 7, 8, 12 optional, skipped) and Stage 4 (all three renewals; still signed in after leaving the page and after force stop). CI #48 APK (`94bb5095`) installed with `adb install -r`; sign-in kept; only Sign out shows.
- **Upstream sync complete (stages 1-3):** Gambit is now on OwnTV v5.2.2 (`42057337`), core 1.0.71, and CI builds with JDK 25 (Gradle 9.8.2).
  - **Stage 3 merged:** v5.2.1 -> v5.2.2 (203 commits), core 1.0.69 -> 1.0.71. Branch `sync/stage-3` (kept): merge `e34f6a0c`, then `1ade9b84` (Gambit CI on JDK 25, its own commit).
  - Merged to main with `--no-ff` in `cd04a844`. CI on `cd04a844`: Gambit CI, Android CI and i18n all passed.
  - Shield: CI run 44125753235 debug APK (`1ade9b84`) installed with `adb install -r` (signer matched). Shield checks 1-4 and 6 passed (rail open/close on Home, Live and Movies; player Left panels and folder pick; More highlight; Home names; reboot lands on the launcher). Check 5 also passed (Darren, on the Shield): locked categories show with a PIN in Live TV, are hidden in the Guide, and are blocked or hidden in the player's folder list. No Gambit crash or ANR in logcat. Report: C:\Users\darre\reviews\gambit-stage3-shield-checks.md.
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

## Next (Darren's order, 2026-11-22)
1. ~~Home row in the Live sports slot~~ (done, see above).
2. **Kids access with parent-approved catalogues:** first confirm whether addons are shared by all adult profiles; then a parent-controlled setting per kids profile that enables addons but limits Discovery to approved catalogues.
3. **Stremio account import**, typed on the phone.
4. **Trakt scrobble and sync** wired to the player and screens (see Open 1 and 2).
5. **The logos.**

## Open
0a. **Tune the "may not play on this TV" rule** once we have more examples of failing files. The stream Darren played for check 3 was likely the `BDRemux 2160p` row, not labelled DV, and it failed the same way: unlabelled UHD REMUXes can also be Profile 7. Possibly flag all 2160p UHD BluRay REMUXes.
0b. **Header shows MPV after the fallback to ExoPlayer** (seen in check 3). Small; not fixed.
0c. **Flaky `GambitStreamProxyTest` port check** (`addresses die when the session closes, and the server stops`, line 207): asserts the freed port refuses a connection, but another socket can reuse it. Failed once on run 45071172117, passed on re-run.
0e. **Flaky `HomeRowBoardTest > posters stay while a refresh is on its way`** (`TimeoutCancellationException`; failed on Gambit CI #64, passed on re-run). Suspicion, UNVERIFIED: `HomeRowBoard.refresh(force = false)` returns early while a refresh job is still active, so on a slow runner the test's second refresh is skipped and the new posters never arrive. That could be a real bug too: Home shown again during a slow refresh would not fetch fresh posters.
0d. **OwnTV_Core fallback bug, reported upstream by Darren.** In `player-core` 1.0.71, `fallbackToExoVod(failure, hardResetFirst = true)` saves `loadGeneration`, then `hardReset()` bumps it again, so the ExoPlayer start 500 ms later sees a mismatch and silently returns (spinner, no error). Affects Movies/Series too. Gambit works around it for addon streams only (retry on EXO_ONLY).
1. **Trakt scrobbling and sync are not wired to the player or any screen yet.** Sign-in is done; nothing calls `TraktScrobbler` or `TraktSync` in normal use. Next action: plan the wiring and get approval before editing.
2. **Trakt calls not proven live** (fixtures only):
   - watchlist path `sync/watchlist/{type}/rank/asc`;
   - scrobbling an episode by show + season + number (`TraktScrobbleItem.EpisodeOfShow`).
   - (`GET /sync/history` without a type is now proven live by the Stage 4 renewal check.)
3. **Later option, not needed now: revoke on sign-out (plan Stage 5).** Sign out only forgets the sign-in on the TV; Trakt still lists Gambit under connected apps until removed there. Request shape without a client secret is unverified.
4. **Unmerged branches, status not checked:** `feature/addons-install` (WIP), `test/fixes-combined`, `claude/android-tv-family-setup-l2njf4`. Next action: review each and decide merge or leave.

## Compiled and tested in CI, never run on a device
- Trakt scrobbling and the sync calls other than history (nothing reaches them yet).
- Addon subtitles when ExoPlayer owns playback (listed through the proxy; designed against mpv's track list).

## Not in Phase 2 (decided)
- Resume position and watch history for addon titles (no Gambit Room table yet), auto-next episode, HLS streams.

## Waiting on Darren
- What to do with the three unmerged branches (item 4).
- Whether to start the Trakt scrobble/sync wiring (item 1).

## Notes
- Commit `ac1c8340` (Trakt models) does not compile its tests on its own; the merged result is fine. Only matters for bisecting.
- Trakt marks `client_secret` as deprecated for on-device apps; `TraktConfig.clientSecret` is optional and only sent when set. Gambit sends none, and sign-in and renewal work without it.
- `gh run list --commit <sha>` can lag a few minutes behind a push; `gh run list --branch <branch>` shows the run sooner.
- CI builds are reproducible: a re-run on the same commit gave a byte-identical APK.
