# alpha32 validation notes

## Reported expedition-list false BUSY

The supplied Copy Log shows selective fruit filtering with `selected=[GREEN]`. The scan accepts only the first visible row (`FRUIT=3`) and then rejects later visible fruit with repeated diagnostics such as:

```text
SKIP[BUSY_DURATION_HEADER] object @(203,992) col=0
SKIP[BUSY_DURATION_HEADER] object @(565,1010) col=1
SKIP[BUSY_DURATION_HEADER] object @(891,1000) col=2
SKIP[BUSY_DURATION_HEADER] object @(203,1418) col=0
```

The same log reports screenshot height `2424` via NAV-GUARD. Those first rejected candidates are at roughly 41% H and the next shown candidate is at roughly 59% H, so they are not lower-edge/bottom-clipped cargo. On the supplied screen, normal AVAILABLE cards display `22小時` below each row. That travel-time text from the previous row lies above the next row and was incorrectly satisfying the unrestricted duration-only BUSY fallback.

## alpha32 fix

The duration-only `BUSY_DURATION_HEADER` fallback now runs only when the candidate center is in the existing LOWER edge-risk zone (`centerY >= 0.78H`). This matches the original documented intent of the fallback: protect bottom-clipped BUSY cards when their progress rail may be covered.

Unchanged BUSY/COMPLETE vetoes:

- full status card
- tolerant status card
- top/bottom partial-card reconstruction
- candidate-local clipped status evidence
- pixel-only grey/red BUSY progress rail
- duration + progress rail (`BUSY_DURATION_PROGRESS`) on any row
- second-frame visual BUSY preflight before FRUIT tap

Therefore a middle-row AVAILABLE travel duration can no longer block the following row merely because it is textually above that fruit, while actual middle-row BUSY still has card/progress evidence.

No GO, OCR engine/model, fruit classification, seedling, filter, selection, fallback, loading, Green X, navigation, or screenshot-throttle logic was changed.
