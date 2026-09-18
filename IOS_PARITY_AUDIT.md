# iOS 11.5.4.31 parity audit

Compared against the user-supplied:
`PikminPilot_Stage11_5_4_31_UNIVERSAL_POSTTAIL_TRANSITION_SETTLE`

## Restored iOS semantics

- `PikminPilotRunnerUITests.selectPikminGrid()`:
  - one screenshot before selection
  - adaptive grid detection once
  - frozen points for the whole selection sequence
- `ImageAutomationDetector.detectPikminFilter()`:
  - magenta component mask
  - purple/pink aligned pair
  - half pair distance = chip spacing
  - no generic row-spacing blend in final target
- `FruitDetector`:
  - BUSY / COMPLETE / partial blocked card wins before fruit/seedling availability
  - plain `花苗` remains rejected
  - `冰藍花苗` and `大花苗` remain accepted only when not blocked

## Android-only additions retained intentionally

- ML Kit Chinese OCR normalization/split-label recovery
- geometry fallback for seedling `前往探險`
- selected-count `(N/MAX)` ACK
- screenshot-to-display gesture mapping
- Green-X settle/retry/ack
- dynamic filter-row swipe Y (the final filter target itself still uses iOS magenta anchors)
