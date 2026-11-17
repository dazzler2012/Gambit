# Handoff: feature/addons-client

Branch `feature/addons-client`, 4 commits on top of `family-build`. Last code commit: `0cfc904`.

## Done
- Stremio addon client in `tv.own.owntv.gambit.addons` (models, URL handling, OkHttp client, registry, repository).
- In the real Gradle build, with `-x verifyI18nLiterals`: `:app:testDebugUnitTest` passed (239 tests, 62 addon tests, 0 failed) and `:app:assembleDebug` succeeded.
- Episode ids: NuvioTV and TuvoraTV percent-encode colons in meta and stream URLs (`URLEncoder.encode`, so `tt1:1:2` is sent as `tt1%3A1%3A2`). Ours already matched. `AddonUrlTest` now pins this.
- The meta fallback to the next matching addon is kept and tested.

## Open
- **Blocker:** `Execution failed for task ':app:verifyI18nLiterals'` / `Hardcoded text check failed`.
  - It flags JSON keys and similar literals in `gambit/addons/net/AddonJson.kt`.
  - Without `-x verifyI18nLiterals`, both the test run and the debug build fail.
- **Needs approval:** run `python tools/i18n/check_hardcoded_strings.py classify-safe` on the addon literals. It writes to `tools/i18n/safe_literals.txt`, which is an existing OwnTV file, so it needs the owner's OK first.
  - Review each flagged literal before classifying it (expected categories are `json`, `protocol` and `uri`; not yet checked).
- Left for later, as agreed: duplicate stream removal, and the Koin wiring in `OwnTVApp.kt`.
- Minor, not acted on: `URLEncoder` and our encodeURIComponent-style encoder treat `! ' ( ) ~` differently. This doesn't affect episode ids.

## Next step
1. Get approval for the `safe_literals.txt` change.
2. Classify the addon literals.
3. Rerun `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug` without `-x`.
4. Push.

## Rules still in force
- Work only on `feature/addons-client`. Don't open a PR.
- Don't edit existing OwnTV files without asking.
- Never log or commit addon URLs; tests use `*.example` hosts only.
- Make no live calls to real addons.

## Environment notes
- Maven Central returned HTTP 429 often in the cloud session. Plain retries worked.
- The SDK needs `platforms;android-37.0` and `build-tools;37.0.0`.
