# Android 0.3.4-alpha16 list-scrolling note

Alpha16 keeps alpha15's detector behavior, but makes expedition-list scrolling more conservative: **24% list-height travel** and a hard **3000 ms settle with no detection** after every list swipe. This is deliberately state/timing focused and does not alter the Pikmin filter-row detector.

---

# Android 0.3.3-alpha15 detector note

The filter step intentionally diverges from the broad iOS magenta-pair search because Android sheet placement
and decor art vary more across devices/accounts. Android now locks the actual circular filter-chip lattice from
red/yellow/blue/cyan anchors, then derives purple/white/pink/rock from canonical slot spacing.

Safety rules:
- no OCR-derived filter-row Y;
- no fixed-Y last-resort filter swipe;
- no swipe unless a 3+ colour lattice is proven;
- no selection step until the target slot is on-screen and visually plausible;
- bright-row filter taps require a visual dimming ACK or one retry on the same proven slot.
