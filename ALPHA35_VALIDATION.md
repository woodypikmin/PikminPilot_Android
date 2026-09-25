# alpha35 validation notes

Baseline: `0.4.8-alpha34`.

Scope: Green X destination-ACK performance only.

## Change

- Added `Detector.detectExpeditionTabPill(Bitmap)` as a strict OCR-free visual proof of the selected `探險` tab.
- `greenXDestinationProven()` now runs this cheap proof first.
- When the selected Expedition pill is proven, ACK returns immediately and ML Kit OCR is skipped.
- If the pill proof misses, the existing alpha34 full `CargoDetector.scan()` (`NAV-GUARD` + list evidence) remains the fallback.
- X absence alone is still never success.
- Same anchored X still arms a controlled retry; stale-coordinate blind taps remain forbidden.

## Fixture sanity checks

Using the previously supplied Expedition-list screenshot (912×2046), the teal selected-tab component is detected around `(590,310)`, with normalized center approximately `(0.647W, 0.151H)`, width about `0.181W`, height about `0.030H`, teal fill about `0.87`, and internal white-glyph fraction about `0.085`.

Using the previously supplied Pikmin selection/GO screenshot, no component satisfies the fast Expedition-tab-pill proof.

## Expected log

Normal successful close:

```text
GREEN-X ACK • anchored X not detected • checking destination page
GREEN-X ACK DESTINATION FAST ✅ • expedition tab pill @(...) • OCR skipped
```

Ambiguous/missed fast proof falls back safely:

```text
GREEN-X ACK DESTINATION OCR ✅ • expedition list proven • nav=... • evidence=...
```

## Build note

This environment does not provide an Android SDK/Gradle wrapper, so no local APK assemble is claimed. The project remains intended for the existing GitHub Actions build.
