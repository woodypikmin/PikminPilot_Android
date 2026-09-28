# Alpha38 validation

Baseline: **0.4.8-alpha37**. New version: **0.4.8-alpha38 / versionCode 37**.

## Scope

Only the Green-X post-tap transition/handoff was changed. Existing GO, BUSY/COMPLETE, fruit/seedling detection, selection/fallback, loading, and navigation scan behavior are retained.

## Fix 1 — destination state wins before any X retry

After a Green X tap, every fresh screenshot is checked in this order:

1. selected Expedition pill fast proof;
2. full `CargoDetector.scan()` OCR/list proof;
3. only if both fail, strict structural carrying Green X (white X + green ring) may authorize a retry.

The Expedition list's own bottom-left X therefore cannot authorize another Green-X tap merely because it is near the old anchor. A remembered/legacy coordinate is never sufficient for a retry.

## Fix 2 — reuse OCR/list scan

If step 2 proves the Expedition list, alpha38 stores that exact bitmap + `CargoDetector.Result`. The following `findCargo()` consumes it once instead of taking another screenshot and running ML Kit OCR again. Fruit filtering, recent-dispatch veto, BUSY preflight, and edge verification still execute normally on the reused result.

## Static checks

- versionName: `0.4.8-alpha38`
- versionCode: `37`
- shared ML Kit Chinese recognizer unchanged
- Green-X first tap still requires anchored two-frame proof
- Green-X retries require current-frame strict structural green-ring X
- destination full scan occurs before any post-tap retry decision
- destination OCR result is single-use cached for next list round

No local APK assemble claim is made unless an Android SDK/Gradle toolchain is available.
