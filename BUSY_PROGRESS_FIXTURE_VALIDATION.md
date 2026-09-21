# BUSY progress-rail fixture validation

Validated against the supplied 912×2048 expedition-list screenshot that contains carried cards with `17分 / 21分 / 18分`, `16分 / 15分 / 16分`, and the lower row `15分 / 12分 / 10分`.

The important previously-missed target is the **middle-bottom green apple under `12分`**.

Using the same broad colourful-component geometry as `CargoDetector`:

- candidate center: approximately **(521, 1941)**
- expedition column: **1 (middle)**
- pixel-only progress rail: **y=1839…1846**
- longest neutral-grey run: **210 px**
- warm-red prefix evidence: **14 px**
- candidate-to-rail separation: about **99 px = 4.8% of screen height**

This satisfies the new `hasBusyProgressRailAboveCandidate()` guard without using OCR, therefore this apple is blocked even if ML Kit fails to read `12分`.

The same test also catches the visible BUSY rails in the rows above. This is intentionally candidate-local: another column/row's rail does not block unrelated AVAILABLE cargo.
