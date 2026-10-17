# Alpha40 validation

Baseline: `0.4.8-alpha39` -> `0.4.8-alpha40` (`versionCode 39`).

## Bug fixed

Alpha39 could positively see the anchored Green X after a failed close tap, set up a retry, then immediately require a second fresh reacquisition at the start of the next attempt. If that second detector pass flickered, the controller blocked the retry even though the preceding frame had just proved the X was still present.

## Alpha40 behavior

- Destination proof remains first and terminates Green-X handling.
- A post-tap frame that proves the same anchored Green X directly supplies the bitmap/tap point for the next tap. There is no redundant reacquisition gate before tap #2/#3/#4.
- `Detector.carryingCloseAnchorStillPresent()` provides a relaxed, anchor-local fallback based on the already-proven Green-X region.
- The fallback requires retained green and rejects high-white regions, so the Expedition list's white bottom-left X cannot authorize a carrying-X retry.
- Ambiguous frames run bounded OCR/list proof instead of repeated OCR on every frame.
- Accessibility gesture `COMPLETED` remains non-authoritative; UI state is still the ACK.

## Expected log

```text
GREEN X TAP #1 ...
GREEN-X ACK • destination not proven • anchored carrying control STILL PRESENT • proof=... • retry #2 authorized from THIS frame
GREEN X STILL VISIBLE ⚠️ • close did not take • immediately proceeding to next tap using the fresh proven frame
GREEN X TAP #2 ...
```

No changes were made to GO, BUSY/COMPLETE, fruit/seedling OCR/classification, Pikmin filter/grid, fallback cursor, loading handling, list swipe timing, or screenshot throttle.
