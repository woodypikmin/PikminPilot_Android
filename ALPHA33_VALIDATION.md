# alpha33 validation notes

## 1. Insufficient selection no longer dies on one page-proof miss

Reported alpha31/alpha32 sequence:

```text
GO RECONCILE COUNT • selected=2/12 • effective-required=12
ERROR[E_GO] • selection page disappeared before GO commit; refusing retry/fallback
```

Root cause in `reconcileGoPreCommit()`:
- the fresh selection count was successfully read,
- but page visibility was independently reduced to `detectFilterRowGeometry(frame) || hasSelectionHeader(ocr)`,
- so one screenshot/OCR false negative threw before the existing fallback/Cancel state machine could run.

alpha33 page proof is intentionally additive and bounded:
- fresh selected/maximum reread
- canonical filter row
- OCR selection header
- OCR Cancel
- existing visual Cancel shape (only while a positive selection is known)

A miss logs `SELECTION PAGE VERIFY` and does **not** permit fallback/Cancel on that unproven frame. Only 3 consecutive non-loading frames with none of the above evidence can produce the disappearance error. Concrete loading resets the miss streak and preserves the existing loading lock.

Expected 2/12 behavior after the normal settle window:
1. strict GO remains off,
2. current selection-page evidence remains available,
3. reconcile returns insufficiency,
4. `SELECTION FALLBACK` runs,
5. because selected > 0, the existing Cancel proof/tap clears the team,
6. the next enabled fallback plan is entered.

No change was made to GO-authoritative commit: if strict GO becomes enabled with selected > 0, GO still wins even below configuredCount.

## 2. Green X target handling

The structural bottom-left X detector and anchored disappearance ACK are unchanged. The controller now:
- retains the initial anchored proof across a single transient detector miss,
- still requires a second near-anchor proof before the first tap,
- uses the white glyph centre when available,
- otherwise applies the already-existing `Detector.refineCarryingCloseTapPoint()` to the legacy green-component hit,
- retains the last proven anchor across a one-frame pre-tap reacquire miss,
- logs both structural and refined tap coordinates,
- still requires the same anchored X to disappear; gesture completion alone is never success.

## Version

- versionName: `0.4.8-alpha33`
- versionCode: `32`

The alpha32 BUSY-duration restriction and all alpha31 GO detector constraints remain in place.

## Local build status

This environment does not provide an Android SDK/Gradle installation for an Android assemble. A `javac` parse pass was used only to check for Java syntax-level errors; unresolved Android/project classes are expected without the Android toolchain. The project is intended to build through the existing GitHub Actions workflow.
