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
