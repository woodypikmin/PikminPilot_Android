# Android 0.3.7-alpha19 reliability note

This release intentionally changes only fruit classification and GO targeting.

- GO: Android had drifted from iOS `detectActiveGO` to an overly broad x>=0.42/y>=0.58 search. alpha19 restores the iOS x>=0.60/y>=0.76 lower-right ROI and adds a centre guard, preventing the middle-bottom drone from being tapped as GO.
- Fruit: the positive fruit-name dictionary remains for diagnostics only. Classification is now exclusion-first: AVAILABLE visual object + non-empty OCR => FRUIT unless the label is seedling/gift/postcard text. This directly tolerates OCR such as 檸樣, 責蘋果, 貴蘋果, or previously unseen fruit names.
- Seedling detection was not changed.

This build keeps the alpha17 gameplay detectors and changes only two failure modes observed in field logs:

1. temporary Android AccessibilityService teardown no longer terminates the Pilot run; it pauses and resumes on rebind within the same process;
2. Android ML Kit lemon OCR variants (`檸樣`, `樟樣`) are repaired only after a fruit visual component has already been associated with that list cell.

No list-swipe, filter-row, Pikmin-grid, GO or Green-X geometry was changed in this build.