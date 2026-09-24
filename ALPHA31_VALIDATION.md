# alpha31 validation notes

## Reported GO false-negative

Input screenshot: 912×2046, selection page visibly showing `6/12` and an enabled orange/red GO at bottom-right.

### alpha30 detector path

`Detector.detectActiveGo()` collected orange/red pixels only from x >= 76% W. On this screenshot that starts at x=693, inside the valid GO disc. The resulting orange component rectangle was clipped on its left edge. `hasGoGlyphPair()` derives its white-letter validation box from that clipped component; the `G` then touched the derived left boundary and was rejected by the existing boundary guard. Only `O` remained, so the required two-glyph proof failed and `detectActiveGo()` returned null.

This matches the controller symptom: GO remains visually enabled but reconcile sees GO off/unknown and can enter GO recovery / selection failure instead of committing.

### alpha31 fix

Only the GO candidate-collection left boundary changes: 76% W -> 72% W. The acceptance contract remains unchanged:

- orange/red high-saturation/value component
- large near-round geometry
- white fraction proof
- two internal white glyph components (G/O)
- viewport center >= 79% W and >= 80% H
- physical-screen center >= 79% W and >= 80% H
- controller-level `isHardSafeGoPoint()` repeats the physical bottom-right lock before the single non-idempotent tap

For the reported screenshot, replaying the detector geometry shows alpha30 retains only one valid white glyph after the 76% crop, while the alpha31 collection margin retains both G and O and passes the same existing glyph-pair and hard-center gates.

No OCR, fruit, BUSY/COMPLETE, filter, fallback, loading, selection-grid, Green X, navigation, or screenshot-throttle logic was changed.
