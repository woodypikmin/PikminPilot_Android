# PikminPilot Android 0.4.1-alpha23

Android port of the user's PikminPilot iOS gameplay loop.

## Preserved behavior

- Keeps the existing Expedition navigation, 24% list swipe, 3-second settle, CTA transition, canonical colour lattice, fast grid selection, Green-X close, return-to-list flow, and exclusion-first fruit OCR.
- Adds a **bottom-clipped BUSY/COMPLETE guard** in addition to the existing top-clipped guard. If an already-carried card enters from the bottom with only its top pastel border and side rail(s) visible, the clipped region is blocked so its fruit cannot be tapped again.
- GO detection is stricter: the candidate must be in the physical bottom-right of the screenshot, be a large peach/orange/red disc, and contain a meaningful white-glyph fraction. This is designed to reject the centre-bottom drone even when both controls are lit.
- Selection fallback now treats **enabled GO as authoritative dispatchability evidence**. Example: configured 12, observed 4/12, GO enabled => commit GO instead of cancelling.
- `selected/maximum` is still read via Accessibility/UI-tree first and screenshot OCR second, but it no longer overrides a clearly enabled GO.
- If GO remains absent after bounded reconcile: `selected < effectiveRequired` => fallback; `selected >= effectiveRequired` => GO/UI recovery error, not a colour fallback.
- GO remains non-idempotent: one confirmed commit tap only, with no blind retry.
- Fallback colour menus are now limited to **岩 / 紫 / 粉紅 / 白**. Red/yellow/blue are no longer offered as fallback choices.
- Busy toast remains diagnostic only.
- GitHub Actions still runs SelectionPolicy unit tests before building the APK.

## Selection examples

- configured=12, live=4/12, GO enabled -> **COMMIT_GO**
- configured=6, live=2/12, GO enabled -> **COMMIT_GO** (game says the smaller team is legal)
- configured=6, live=1/2, GO disabled after reconcile -> **FALLBACK**
- configured=6, live=2/2, GO still missing after reconcile -> **GO_RECOVERY**, not fallback

## Build

The repository is intended to build through GitHub Actions. The workflow installs Android SDK platform 36 / build-tools 35.0.0 and Gradle 8.13, runs `:app:testDebugUnitTest`, then builds `:app:assembleDebug`.

## 0.4.1-alpha23 changes

- Added a **sticky selection-plan cursor** for one START session. Once PRIMARY advances to FALLBACK-1, the next cargo begins directly at FALLBACK-1 instead of wasting time retrying the exhausted primary colour. The same rule applies F1→F2 and F2→F3.
- PRIMARY→F1, F1→F2 and F2→F3 now use the exact same `advanceSelectionPlan()` transition path and the existing safe reset / zero-selected in-place switch state machine.
- The cursor is monotonic during a run and never moves backward after a later fallback has been reached. Pressing START again resets the cursor to PRIMARY.
- Existing GO-authoritative commit, zero-select/no-Cancel in-place fallback, BUSY guards, navigation, transport and Green-X flow are unchanged.
- Fallback colour choices remain limited to Rock / Purple / Pink / White.
