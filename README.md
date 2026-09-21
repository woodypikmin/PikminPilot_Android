# PikminPilot Android 0.3.8-alpha20

Android port of the user's PikminPilot iOS gameplay loop.

## alpha20 changes

- Keeps the existing Expedition scan, CTA, filter lattice, grid selection, Green-X close, list swipe and 3-second list settle flow.
- Adds **Selection Plan fallback state machine**: Primary + up to 3 enabled fallbacks.
- Every fallback has its own Pikmin type and configured count.
- Live `selected / maximum` is the selection source of truth. Accessibility/UI-tree is attempted first; screenshot + ML Kit OCR is the fallback.
- `effectiveRequired = min(configuredCount, liveMaximum)`.
- The transient `這隻皮克敏似乎很忙` message is diagnostic only; it never triggers fallback by itself.
- A fallback can happen only while GO has not been sent. The previous selection is cancelled/reset before the next filter is applied.
- If Cancel returns to expedition detail, Pilot re-enters `前往探險` and verifies a fresh selection page.
- GO is non-idempotent: it is tapped at most once after `selected >= effectiveRequired` **and** the strict GO detector confirms enabled state.
- GO detector is now restricted to a **large orange/red component in the lower-right corner**, excluding the centre/bottom drone control.
- Adds a **top-clipped BUSY/COMPLETE card guard** for already-carried fruit partly hidden under the Expedition navigation row. It requires the bottom status border plus both vertical side rails and blocks only up to that border, so nearby valid fruit below is not suppressed.
- Fruit exclusion-first OCR and seedling logic remain unchanged from alpha19.
- GitHub Actions runs the SelectionPolicy truth-table unit tests before building the APK.

## Fallback example

Primary = 紫 × 6
Fallback 1 = 岩 × 2
Fallback 2 = 紅 × 2
Fallback 3 = 黃 × 2

For each plan:

1. apply colour filter
2. select configured number
3. read live `selected/maximum`
4. compute `effectiveRequired = min(configured, maximum)`
5. if insufficient: cancel/reset and advance to the next enabled plan
6. if satisfied: reconcile GO state; only then commit GO once

## Build

The repository is intended to build through GitHub Actions. The workflow installs Android SDK platform 36 / build-tools 35.0.0 and Gradle 8.13, runs `:app:testDebugUnitTest`, then builds `:app:assembleDebug`.
