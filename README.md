# PikminPilot Android 0.3.4-alpha16

## Expedition-list swipe settle

This build intentionally changes only the **expedition fruit/seedling list scrolling** from alpha15:

- List swipe travel reduced from about **30%** of the detected list height to about **24%**.
- Swipe duration is 420 ms for a gentler drag with less fling/inertia.
- After every list swipe the pilot waits a fixed **3000 ms**. During this settle window it performs **no screenshot, OCR, cargo detection, or coordinate selection**.
- After the 3-second settle it captures a completely fresh frame and re-runs the list detector.
- The filter-row / pink Pikmin colour-chip logic, CTA, GO, Green-X, BUSY/COMPLETE blocking, and copy-log behavior remain from alpha15.

Expected log:

```text
EXPEDITION LIST SWIPE • amplitude=0.24H • direction=DOWN • (...) → (...)
EXPEDITION LIST SETTLE ⏳ • 3000ms • no screenshot / OCR / detection
ROUND ... • capture → OCR/list detector
```

Build marker: `BUILD 0.3.4-alpha16`

---

# PikminPilot Android 0.3.3-alpha15

## alpha14 stability fixes

This build is based on 0.3.1-alpha13 and targets the failures visible in the copied diagnostic log.

### 1. Pink/Purple/White/Rock filter selection
The previous build could detect a magenta pair inside Pikmin/Decor artwork below the real colour-chip strip.
The log exposed this directly: the detected "pink" Y jumped between roughly 0.50H and 0.64H from round to round.

alpha14 now:
- locates the actual chip row first;
- requires a 5+ chip horizontal chain, or the OCR anchor `飾品/自動`;
- runs the iOS purple/pink magenta-pair detector only inside that row;
- refuses a broad-screen magenta guess when no reliable row exists.

### 2. Green X
The previous structural X verifier could jump from the real close button around `(0.10W, 0.93H)` to an unrelated white-X-like shape near the left edge.
The supplied log showed exactly this jump: `(107,2256)` -> `(20,1888)`.

alpha14 now:
- restricts X detection to Pikmin Bloom's bottom-left close-button zone;
- anchors all verification/retries to the first candidate;
- rejects large coordinate jumps;
- taps the same anchored control;
- considers the tap successful only when that same X disappears for two consecutive frames.

This also removes the expensive two-frame OCR Expedition-list ACK after every X, which was adding several seconds between rounds.

### 3. BUSY/COMPLETE cards and nearby valid seedlings
alpha13 added a same-column raw status-border safety rule. It was too broad: a BUSY card border could suppress a different valid seedling immediately below/above it.

alpha14 returns to the iOS rule:
- only a reconstructed BUSY / COMPLETE / partial card blocks a candidate;
- a lone horizontal border in the same column does not block neighbouring items;
- the broad recent-dispatch proximity latch is replaced with an exact/narrow latch:
  it only blocks the same normalized label at essentially the same screen slot for up to 3 rounds.
  This catches OCR variants like `藍色花苗` -> `籃色花苗` without hiding nearby white/yellow seedlings.

### 4. List swiping
The 30% overlapping list swipe is retained. If the current viewport has no AVAILABLE matching item after the card-only filter, the pilot swipes down and continues scanning.

### Diagnostics
`Copy Log` remains available. Important new messages include:

- `FILTER ROW LOCK ✅`
- `FILTER ROW LOCK MISS`
- `GREEN X VERIFY • anchored`
- `GREEN X VERIFY • rejected jump`
- `GREEN-X ACK • anchored X absent`
- `GREEN-X ACK ✅`

Build marker:

`BUILD 0.3.3-alpha15`

## 0.3.3-alpha15 filter-row safety change

The Pikmin colour filter no longer uses a broad magenta search, OCR Y fallback, or blind last-resort swipe.
It locks the canonical chip lattice from at least three circular colour anchors (red/yellow/blue/cyan), infers
purple/white/pink/rock by slot geometry, and only swipes on a proven row when the requested slot is genuinely
off-screen. If the row is not proven, automation stops at `E_FILTER` instead of dragging the Pikmin grid.

When the row is initially bright, the controller also verifies that the row dims after the filter tap. A failed
filter tap is retried once on the same canonical slot before selection is allowed to continue.
