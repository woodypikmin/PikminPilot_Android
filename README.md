# PikminPilot Android 0.2.5-alpha7

Android port of the PikminPilot automation flow.

## 0.2.5-alpha7 — Universal filter row + seedling CTA fallback

This build keeps the working 0.2.4 automation flow and changes only the two areas that were device-layout sensitive.

### 1. Universal Pikmin colour row

The old build swiped the filter row at a hard-coded `0.404H`. That worked on one phone but failed when Pikmin Bloom placed the selection sheet at a different vertical position.

The new build scans the actual screenshot for the horizontal chain of coloured filter circles, estimates the row Y and chip spacing, and swipes through that measured row. The purple/white/pink/rock target is then located relative to the same detected row instead of assuming a fixed screen position.

Useful log lines:

```text
FILTER ROW AUTO ✅ • frame=... • chips=... • y=... (...H) • spacing=... • swipe=(...) → (...)
FILTER ROW AUTO MISS ⚠️ • fallback swipe ...
```

### 2. 冰藍花苗 / 大花苗 前往探險

Seedling detail pages still try ML Kit OCR first. If OCR does not return `前往探險`, the controller now searches for the wide green/teal outlined CTA pill by geometry. The aspect-ratio and central-position gates are designed to reject the blue seedling pot artwork above it.

Useful log lines:

```text
花苗 前往探險 OCR found ✅ ...
花苗 前往探險 GEOMETRY found ✅ ...
花苗 前往探險 waiting ... • lower OCR=...
```

OCR also tolerates simplified/traditional variants, one damaged character, and split `前往` + `探險` line boxes.

## Expected workflow

1. Leave Pikmin Bloom open on the Expedition list.
2. Return to PikminPilot and choose cargo, Pikmin type/count, and run count.
3. Press START.
4. Pilot gives Pikmin Bloom 3 seconds to return to the foreground.
5. It scans the Expedition list, opens a safe fruit/seedling, taps `前往探險`, detects the colour-filter strip, selects the configured Pikmin, taps GO, then closes the carrying screen with the green X.

## Build on GitHub

Push the repository to GitHub and run **Actions → Build Android APK → Run workflow**. Download the generated debug APK artifact.

On app launch the log should contain:

```text
BUILD 0.2.5-alpha7 • universal filter-row detector • seedling CTA OCR+geometry
```
