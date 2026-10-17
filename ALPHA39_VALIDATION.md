# Alpha39 validation

Baseline: 0.4.8-alpha38.

## Scope

Only the Green-X post-tap ACK/retry path and version metadata changed. GO, BUSY/COMPLETE, fruit/seedling detection, OCR engine, selection/fallback, loading, filter, and scan geometry are unchanged.

## Regression fixed

Alpha38 acquired the initial carrying X with `Detector.detectCarryingClose()` (universal strict + legacy fallback) but required `Detector.detectCarryingCloseGlyph()` (strict-only) after the tap. A failed close gesture could therefore leave the real X on screen while the retry path claimed there was no strict X and eventually emitted `E_GREEN_X_ACK`.

Alpha39 uses `Detector.detectCarryingClose()` again on each fresh retry/ACK screenshot, and accepts it only when the candidate remains within the original anchored tolerance. A fresh Expedition-tab proof always wins and terminates X handling before any retry. A remembered coordinate alone never authorizes a tap.

## Expected log on failed first close

```
GREEN X TAP #1 ... dispatch=COMPLETED
GREEN-X ACK • destination not proven • anchored Green X STILL PRESENT on current frame • close did not take; retrying
GREEN X STILL VISIBLE ... retrying X
GREEN-X CARRYING STATE ... current-frame anchored Green X reacquired with universal detector • retry authorized
GREEN X TAP #2 ...
```

## Expected log after successful return to list

```
GREEN-X ACK DESTINATION FAST ✅ ...
```

or, if geometry misses but OCR/list proves it:

```
GREEN-X ACK DESTINATION OCR ✅ ... X handling terminated
```

The Expedition list's own white bottom-left X cannot authorize a retry because alpha39 still requires a current-frame carrying Green-X detector result near the original anchor.
