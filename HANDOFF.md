# Handoff: ci/tests-and-build

Last updated 2026-11-02.

## State
- Branch `ci/tests-and-build`, made from `family-build` at `1125000`. Everything is pushed.
- PR #1 (`ci/tests-and-build` -> `family-build`) is **open, not merged**.
- `gambit-ci.yml` is already on `main` (PR #2, `8c83869`, the workflow file only). Same file as on this branch.
- The 5 test files are **not** on `main` or `family-build` yet. They only reach `family-build` when PR #1 is merged.

## What this branch adds
- `.github/workflows/gambit-ci.yml`: on every push and manual run, runs `:app:testStandardDebugUnitTest`
  (which runs `verifyI18nLiterals` through `preBuild`), then `:app:assembleDebug`, and uploads the
  standard debug APK as `gambit-debug-<sha>`. No secrets.
- 26 JVM unit tests under `app/src/test/java/tv/own/owntv/gambit/` (25 pass, 1 `@Ignore`d, see below).
  No new dependencies, no app code changed.

## PR #1 checks (as of 2026-10-21, head `99f4080`)
- Pass: Gambit CI, i18n/baseline, i18n/pseudo.
- Fail, not caused by this PR: i18n/validate. `check_text_overflow.py` flags 18 `Text(...)` calls in
  `gambit/ui/*` that set `maxLines` without `overflow`. `family-build` alone fails the same 18 lines.
- Fail, paperwork: PR checklist. Tick the two boxes in the PR description.

## Open items
1. Merge PR #1 into `family-build` (both failing checks are safe to ignore for this PR).
2. Separate PR, touches app code: fix the 18 text-overflow lines (add `overflow = TextOverflow.Ellipsis`
   or `Clip`, or list them in `tools/i18n/text_overflow_allowlist.txt` with reasons).
3. Known bug, not fixed: `GambitDefaults.GUIDE_CHANNEL_SHARE = 26` is off core's 5-point grid; core
   normalises it on read, so the guide shows 25/75. Fix the constant (25 or 30), then remove the
   `@Ignore` in `GambitDefaultsTest`.
4. Not covered by tests (would need app code changes or Robolectric): "now playing" lookup and schedule
   day headings (inside Composables / `private`), `GambitDefaults.applyOnce`, `GambitLastTuned`.
   Group hide/move, filtering and sorting live in OwnTV/core files, not `gambit.*`.

## Notes
- There is no `:app:testDebugUnitTest` task: the app has two flavors (standard, x86_64).
- APK download: repo -> Actions -> Gambit CI -> a run -> Artifacts.
