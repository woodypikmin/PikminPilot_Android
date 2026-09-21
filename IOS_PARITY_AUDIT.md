## 0.4.3-alpha25 Android lower-edge BUSY note

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
