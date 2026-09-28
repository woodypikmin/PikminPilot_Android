# alpha37 validation notes

## Scope
Minimal Green-X retry safety fix on top of alpha36.

## Reproduced race in alpha36
alpha36 correctly checked the returned Expedition tab before checking X-like controls, but the retry loop still carried `retryArmed=true` across frames. If a post-tap ACK frame still showed the carrying Green X, the next retry was armed. The game could then finish transitioning to the Expedition list before the next tap frame. If that fresh frame temporarily missed the Expedition-tab fast proof and no carrying X was detected, alpha36 still proceeded with the old `anchorFrame/tapAnchor`. That stale coordinate is almost exactly where the Expedition sheet's own bottom-left X appears, so the sheet could be closed accidentally.

This is a stale-coordinate race, not evidence that the stable Expedition-tab detector is wrong. On the supplied 912x2046 Expedition screenshot, the existing fast pill geometry identifies the selected teal Expedition tab, while the carrying-X detector does not classify the white-sheet X as the carrying Green X.

## alpha37 change
- Every Green-X tap, including retry taps, now requires a **current-frame reacquisition** of the same anchored carrying X.
- A remembered/prior X proof may authorize waiting/reacquisition only; it can never authorize a tap.
- If the current frame has no anchored carrying X, the controller enters a bounded HOLD state and repeatedly checks:
  1. fast Expedition-tab destination proof -> success, or
  2. same anchored carrying X on the current frame -> retry is allowed.
- No tap is dispatched while neither state is proven.
- Full OCR/list destination proof is fallback-only at the end of the bounded hold window, avoiding repeated OCR on the normal transition path.
- Added a final internal pre-tap guard so future control-flow changes cannot dispatch a stale Green-X coordinate without current-frame proof.

## Expected log around the fixed race
When transition is in progress:

```
GREEN X TARGET • current frame has no anchored carrying X ...
GREEN X RETRY HOLD 🛑 • no current-frame anchor • stale-coordinate tap forbidden
```

Then either:

```
GREEN-X ACK DESTINATION FAST ✅ ...
```

or, if the carrying X really remains:

```
GREEN-X STATE ✅ • same anchored carrying X reacquired on current frame before retry
GREEN X TAP #N ...
```

There must not be a `GREEN X TAP #N` after a frame that has neither current-frame carrying-X proof nor destination proof.

## Preserved behavior
No changes to BUSY/COMPLETE, fruit/seedling OCR/classification, filter lattice, selection grid, sticky fallback state machine, loading behavior, GO detection/commit, NAV-GUARD, or screenshot error=3 throttle.

## Version
- versionName: `0.4.8-alpha37`
- versionCode: `36`

No local Android APK build is claimed unless an Android SDK/Gradle environment is available.
