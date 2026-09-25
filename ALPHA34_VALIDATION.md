# alpha34 validation notes

## Scope

Minimal Green X transition-ACK correction on top of alpha33.

## Root cause fixed

alpha33 did capture verification screenshots after tapping Green X, but its ACK loop counted two frames where `detectCarryingClose()` returned `null` as success. A detector flicker could therefore be mistaken for a successful transition even if Pikmin Bloom was still showing the carrying page.

## alpha34 contract

1. Gesture `COMPLETED` is still not success.
2. X-detector absence alone is never success.
3. Success requires positive Expedition-list proof from the existing list detector: `navGuardProven` plus at least one cargo/status-card evidence item.
4. If the same anchored X is still visible, the existing refined tap target is refreshed and a bounded retry is allowed.
5. If neither destination proof nor the same anchored X can be established, the controller refuses a stale-coordinate retry and reports `E_GREEN_X_ACK`.
6. An unrelated X-like candidate never causes the anchor to jump.

## Regression constraints retained

- GO remains non-idempotent and unchanged from alpha31+.
- alpha32 BUSY duration-header lower-edge fix retained.
- alpha33 selection-page bounded proof/fallback behavior retained.
- Green X still uses anchored structural detection and screenshot→display coordinate mapping.
- Fruit OCR/classification, seedlings, canonical filter lattice, frozen 5-column grid, sticky fallback cursor, loading semantics, NAV-GUARD, and screenshot error=3 throttle are unchanged.

## Version

- versionName: `0.4.8-alpha34`
- versionCode: `33`

## Local build

No Android SDK/Gradle toolchain is available in this environment, so no local APK assemble success is claimed. Use the existing GitHub Actions workflow.
