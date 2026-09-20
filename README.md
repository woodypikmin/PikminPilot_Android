# PikminPilot Android 0.3.7-alpha19

## alpha19 focused fixes
- **GO is bottom-right only.** `detectActiveGo()` is restored to the iOS-proven ROI (`x >= 0.60W`, `y >= 0.76H`) plus a lower-right centre guard, so the centre-bottom drone/expedition icon cannot be selected as GO.
- **Fruit OCR is exclusion-first.** After an AVAILABLE 3-column visual object is found, any non-empty nearby OCR is treated as fruit unless it contains seedling/gift text. This removes dependence on exact fruit spelling such as `檸檬`, `青蘋果`, etc.
- Explicit non-fruit exclusions include `花苗`, `禮/礼`, `贈/赠`, `稀有`, `gift/present`, and postcard text. Seedling handling itself is unchanged.
- Existing 24% list swipe, 3000 ms settle, NAV-GUARD, filter lattice, anchored Green-X, and accessibility auto-resume are retained.


Reliability hotfix based directly on alpha17.

## Fixes in this build

### Accessibility service reconnect / resume
A real alpha17 log showed Android destroying the AccessibilityService during round 4 (`STOP • Accessibility service stopped`) and binding it again several minutes later. Alpha17 treated service destruction as a user stop, so the run could never continue.

Alpha18 now treats temporary AccessibilityService loss as recoverable:

- current round/config/completed count are preserved in the same app process;
- automation pauses instead of calling `stop()`;
- screenshot/tap/swipe operations wait for Android to rebind the service;
- when the service reconnects, the current stage resumes automatically;
- the normal Stop button still stops immediately;
- reconnect wait has a 10-minute safety timeout.

Useful log lines:

```text
ACCESSIBILITY LOST ⚠️ ... automation PAUSED
ACCESSIBILITY WAIT ⏸️ ... preserving round state
ACCESSIBILITY RECONNECTED ✅ ... resumeStage=...
ACCESSIBILITY WAIT END ✅
```

### Lemon OCR repair
On real Android logs, ML Kit rendered `檸檬` as `檸樣` and sometimes `樟樣`, causing a valid lemon to be logged as `SKIP[UNKNOWN_LABEL]`. Alpha18 adds a narrow lemon repair after the visual fruit component has already been detected in that cell.

Accepted OCR variants now include:

- `檸檬` / `柠檬`
- `檸樣` / `柠样`
- observed `樟樣` / `樟样`

Repaired lemons appear as:

```text
SCAN-DIAG ACCEPT[FRUIT:LEMON_OCR_REPAIR] 檸樣:...
```

Alpha17's 24% list swipe, 3000 ms settle, dynamic NAV-GUARD, canonical filter lattice, copy-log UI and anchored Green-X are unchanged.

Build marker: `BUILD 0.3.7-alpha19`.
