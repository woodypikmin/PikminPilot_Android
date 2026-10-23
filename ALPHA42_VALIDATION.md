# Alpha42 validation

Baseline: `0.4.8-alpha41` -> `0.4.8-alpha42` (`versionCode 41`).

## User-visible bug

The Green X could be positively detected and tapped, yet later post-tap frames could miss both the destination detector and the Green-X rediscovery detector. Alpha41 then returned `GREEN X ACK UNRESOLVED` even though the original Green-X anchor had already been verified. This made the controller stop instead of trying the known close control again.

A separate failure mode was that the Expedition list's white-disc / black-X control could appear at nearly the same coordinates. That control must never be tapped as a Green-X retry.

## Alpha42 state rule

Once the carrying Green X has passed the existing two-frame anchored verification, its anchor is **latched** for this close operation. After each tap:

1. Proven Expedition destination -> success; terminate Green-X handling.
2. White-disc / black-X replacement at the old anchor -> success for the close operation; the carrying Green X is gone, terminate X handling and never tap the list X.
3. Green-X rediscovered globally or by anchor-local green fingerprint -> guarded retry.
4. Neither destination nor white-list-X nor Green-X rediscovery -> do **not** fail immediately. Keep the verified anchor and perform the next retry only after a fresh final guard.
5. The fresh final guard always checks destination and white/list-X replacement before using the latched anchor.
6. Maximum tap count remains four; the controller still fails after the retry limit if neither transition nor post-close replacement is ever observed.

This means a detector flicker can no longer cancel a retry after the Green X was already positively verified, while the second white/list X remains an absolute stop condition.

## Expected logs

If rediscovery flickers but the page has not transitioned:

```text
GREEN X TAP #1 ...
GREEN X ACK AMBIGUOUS → RETRY • ... retain verified Green-X anchor for guarded retry #2
GREEN X RETRY LATCH ✅ • ... detector flicker cannot cancel retry
GREEN X TAP #2 ...
```

If the close succeeded and the Expedition list white X appears:

```text
GREEN-X ACK POST-CLOSE ✅ • white-disc/black-X replacement at old Green-X anchor ... • terminate X handling
```

No second tap is permitted on that white/list X.

No changes were made to GO, BUSY/COMPLETE, fruit/seedling OCR/classification, Pikmin filter/grid, fallback cursor, loading handling, list swipe timing, or screenshot throttle.
