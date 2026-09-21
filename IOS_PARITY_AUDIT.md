## 0.4.6-alpha28 delta

- Added OCR-independent, candidate-local BUSY progress-rail detection.
- Rationale: a captured 912×2048 fixture contains a middle-bottom green apple under a `12分` BUSY header. Pixel testing finds the rail around y≈1842 and the apple component center around (521,1941); the old OCR-gated path could miss it when `12分` was not recognized.
- The new rail detector blocks that candidate from pixels alone (long grey track + red prefix), while retaining existing iOS-derived card-first BUSY/COMPLETE logic.

## 0.4.6-alpha28 delta

Android adds a device-tolerant BUSY semantic signature that does not replace the iOS card-first RGB/geometry detector:

1. Candidate is first rejected by reconstructed BUSY / COMPLETE / BLOCKED cards.
2. For a remaining candidate, a same-column duration header ABOVE the cargo plus the long neutral progress rail beneath that header is sufficient BUSY evidence on any visible row.
3. For lower-edge clipped cards only, duration-above may remain sufficient when the progress rail is hidden by overlays/system UI.
4. Top-clipped cards continue to use border/rail geometry, since their duration header is off-screen.

This avoids relying on a particular pale-red border RGB on every Android phone while preserving directional card-local semantics.

## 0.4.4-alpha26 Android reliability note

- BUSY semantic time evidence is now duration-based rather than `日+小時`-specific. `N分`, `N分鐘`, `N小時`, `N日...` etc. are accepted only when they are above the candidate in the same column.
- Selection fallback is paused while hollow roster loading rings are present. Loading is an UNKNOWN roster state, not evidence of "zero Pikmin".
- With a proven `selected=0`, GO absent, and selection page still visible, Android changes fallback colour in place even if Cancel is visible; there is no selected set to clear.
- No changes to GO non-idempotence, sticky fallback ordering, cargo swipe/settle, or Green-X semantics.

## 0.4.4-alpha26 Android lower-edge BUSY note

The iOS card-first RGB/geometry detector remains the base. Android additionally treats a same-column remaining-return-time header above a lower-edge candidate as BUSY evidence, and requires a second-frame AVAILABLE confirmation before tapping edge-risk cargo. This is intentionally Android-only hardening for overlays, screenshot colour shifts, and transition frames; it does not change seedling classification or the selection fallback state machine.

## 0.4.2-alpha24 Android-only stability note

This release does not change iOS selection semantics. It only makes Android Accessibility screenshots tolerant of vendor rate limiting (`takeScreenshot error=3`) and retains a longer diagnostic log buffer.

# Android 0.4.1-alpha23 reliability note

This release is a minimal delta from alpha22. It does not change cargo scanning, GO detection, Green-X, expedition navigation, or the fallback reset rules.

## Sticky fallback progression

The active selection plan now has a monotonic cursor for the lifetime of one START session:

- PRIMARY insufficient → advance to FALLBACK-1; if F1 succeeds, the next cargo starts at F1.
- FALLBACK-1 later insufficient → advance to FALLBACK-2; the next cargo starts at F2.
- FALLBACK-2 later insufficient → advance to FALLBACK-3; the next cargo starts at F3.
- A later plan never falls back to an earlier exhausted colour during the same run.
- A new START resets the cursor to PRIMARY.

All plan-to-plan transitions use one `advanceSelectionPlan()` path. That path updates the sticky cursor and then invokes the same pre-existing reset state machine: Cancel-and-clear when there is a live selection, or zero-selected/no-Cancel in-place colour switching when it is safe.

## Safety retained

- Enabled GO + selected > 0 remains authoritative and commits immediately.
- GO absent + count satisfied remains GO/UI recovery, not a colour fallback.
- GO remains non-idempotent and is never blindly retried.
- Fallback choices remain 岩 / 紫 / 粉紅 / 白.
- Existing BUSY/COMPLETE guards and list scanning are unchanged.

## 0.4.4-alpha26 delta

- BUSY lower-edge semantic guard now treats generic duration-like text above the candidate as BUSY evidence (`日/天/小時/分鐘/分/秒`), instead of assuming a day+hour pair. This shortcut is only for bottom-clipped cards whose upper half is visible; top-clipped/lower-half-only cards remain border/rail driven.
- Selection loading placeholders no longer block slot taps. Geometric taps are allowed while sprites load, but Cancel/Fallback is locked until loading clears and two non-loading frames settle.
- Strict bottom-right GO remains authoritative during loading. If GO lights, commit; do not cancel because selected/max is temporarily stale.
- If loading clears at 0/N with GO off, retry the same plan once before fallback. Persistent loading fails closed without Cancel/Fallback.
