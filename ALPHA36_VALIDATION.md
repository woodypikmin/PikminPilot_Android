# alpha36 validation notes

## Scope
Minimal fix on top of alpha35 for Green-X ACK ordering only.

## Reproduced failure mode
After the carrying-page Green X is successfully tapped, Pikmin Bloom returns to the Expedition list. That list also has a bottom-left X close to the previous anchor. alpha35 evaluated `Detector.detectCarryingClose()` before the destination-page proof in several retry/ACK frames. An X-like candidate near the old anchor could therefore be treated as the same carrying X and tapped, closing the Expedition sheet.

## alpha36 change
- On the initial frame, every retry frame, the post-tap ACK frame, and ACK recovery frames, `detectExpeditionTabPill()` is evaluated before any carrying-X candidate.
- A positive selected Expedition tab is terminal ACK: return success immediately and never tap a bottom-left X on that frame.
- Full OCR/list proof remains fallback-only when the fast tab proof is absent and no same anchored Green X is positively established.
- The existing rule remains: X absence alone is never success.

## Preserved behavior
No changes to BUSY/COMPLETE, fruit/seedling OCR and classification, filter lattice, selection grid, fallback cursor/state machine, loading rules, GO detection/commit, NAV-GUARD, or screenshot error=3 throttle.

## Version
- versionName: `0.4.8-alpha36`
- versionCode: `35`

No local Android APK build is claimed unless an Android SDK/Gradle environment is available.
