# 0.4.8-alpha35 — fast Green X destination ACK

alpha35 is a minimal continuation of alpha34. It keeps the page-level Green X safety fix, but removes ML Kit OCR from the normal successful close path.

After an anchored Green X tap, an X miss is still **not** success by itself. The controller now first checks a strict OCR-free visual proof for the selected teal `探險` navigation pill (normalized top-row geometry + teal fill + internal white glyph pixels). If that proof is present, the Expedition list transition is acknowledged immediately and the expensive OCR/list scan is skipped.

If the visual pill proof is absent or ambiguous, alpha35 falls back to the full alpha34 `CargoDetector.scan()` proof (`NAV-GUARD` + list evidence). The same anchored X still triggers a controlled retry. Therefore speed improves on the normal path without restoring the unsafe alpha33 rule that detector absence alone means success.

Preserved unchanged: alpha34 anchored-X page ACK semantics, alpha33 selection/fallback proof, alpha32 BUSY duration fix, alpha31 strict GO ROI fix, ML Kit Chinese OCR, fruit/seedling classification, single-tap filter, sticky fallback cursor, loading-safe GO watch, and screenshot error=3 throttle.

---

# 0.4.8-alpha34 — Green X page-level transition ACK

alpha34 is a minimal continuation of alpha33. It changes only the Green X close/ACK path.

The important correction is that **a missed X detector is no longer treated as proof that the carrying page closed**. After each X tap, alpha34 re-screens the UI and requires positive proof that the Expedition list has returned (`NAV-GUARD` + at least one list item/card). If the same anchored X is still visible, it retries that same control. If neither the destination page nor the anchored X can be proven, it fails closed rather than tapping a stale coordinate.

This preserves the existing anchored structural X detector, white-X tap refinement, screenshot→display coordinate mapping, and the rule that Accessibility gesture `COMPLETED` is never an ACK by itself.

No BUSY/COMPLETE, fruit/seedling OCR, selection/fallback, loading, GO, filter, or list-scan behavior was changed.

---

# 0.4.8-alpha33 — selection fallback proof + Green X tap refinement

Minimal delta from alpha32. This release addresses two controller-level false failures without changing the stable cargo, OCR, filter, loading, or GO contracts.

## Selection fallback / Cancel

Reported failure:

```text
GO RECONCILE COUNT • selected=2/12 • effective-required=12
ERROR[E_GO] • selection page disappeared before GO commit; refusing retry/fallback
```

The controller previously treated a single frame that missed both the canonical filter row and OCR selection header as proof that the selection page had disappeared, even when a fresh selected/maximum reread had just succeeded. That could abort before `advanceSelectionPlan()` reached the existing Cancel/fallback state machine.

alpha33:
- A fresh `selected/maximum` reread now counts as current selection-page proof.
- OCR `取消` and the existing lower-left Cancel shape are also accepted as page evidence.
- One detector/OCR miss no longer aborts. The controller requires 3 consecutive non-loading frames with **no** fresh count, filter row, selection header, or Cancel evidence before declaring the selection page gone.
- Any concrete loading frame clears the missing-page streak and continues the existing no-Cancel/no-fallback loading watch.
- Once settled and still below `effectiveRequired` with GO off, the existing fallback path runs normally; selected > 0 still requires Cancel before changing colour.

## Green X

The anchored structural detector and disappearance ACK remain the safety contract. alpha33 only makes the already-detected target less brittle:
- The initial `waitPoint()` hit remains the first anchored proof; one transient detector miss no longer resets it to zero. A second near-anchor hit is still required before tapping.
- Structural anchor and tap target are tracked separately.
- If the white-X glyph detector is available, its centre is used directly for the tap.
- If only the legacy green-component path is available, the existing `refineCarryingCloseTapPoint()` is now actually used to move the gesture toward the white X instead of a gradient-biased green component centre.
- Before retries, a one-frame reacquire flicker keeps the last proven anchored target instead of invalidating it.
- Gesture `COMPLETED` is still not success; the original anchored X must disappear before ACK.

Preserved unchanged: alpha32 BUSY duration fix, alpha31 strict GO ROI fix, GO non-idempotency, fruit/seedling recognition, ML Kit Chinese OCR, NAV-GUARD, 24% swipe + 3 s settle, single-tap colour filter, fixed 5-column selection grid, sticky Primary/F1-F3 cursor, loading-safe GO watch, and screenshot error=3 throttle.

---

# 0.4.8-alpha32 — BUSY duration cross-row false-positive fix

Minimal delta from alpha31. This fixes a real expedition-list case where selective fruit filtering (for example GREEN) could see visible matching fruit but keep swiping because ordinary AVAILABLE travel-time OCR such as `22小時` from the row above was mis-associated with the next row and emitted `SKIP[BUSY_DURATION_HEADER]`.

Fix:
- `BUSY_DURATION_PROGRESS` remains valid on any row: duration + actual grey/red BUSY progress rail still vetoes immediately.
- Pixel-only `BUSY_PROGRESS_RAIL`, full/partial BUSY/COMPLETE cards, local status borders, and second-frame FRUIT BUSY preflight are unchanged.
- The **duration-only** `BUSY_DURATION_HEADER` fallback is restored to its documented purpose: lower-edge / bottom-clipped candidates only (`centerY >= 78% H`), matching the controller's existing LOWER edge verification zone.
- Middle rows no longer let the previous AVAILABLE row's normal travel duration block the next row.

Preserved unchanged: alpha31 strict GO ROI fix, fruit colour classification, NAV-GUARD, flower-seedling logic, 24% swipe + 3 s settle, single-tap Pikmin filter, fixed 5-column selection grid, sticky Primary/F1-F3 fallback, loading-safe GO watch, Green X ACK, and screenshot error=3 throttle.

---

# 0.4.8-alpha31 — GO candidate ROI crop fix

Minimal delta from alpha30. The strict GO acceptance contract is unchanged: enabled GO still requires a large orange/red bottom-right disc, two internal white G/O glyph components, and an absolute center at or beyond 79% W / 80% H; the controller still re-checks the hard bottom-right point before the one non-idempotent tap.

Fix:
- The orange/red **candidate-collection ROI** now begins at 72% W instead of 76% W. This is intentionally only a collection margin, not a looser acceptance gate.
- On the reported 912×2046 selection screenshot, alpha30 clipped the left side of the valid GO orange component at x=693 (=76% W). That made the white `G` touch the derived glyph-validation boundary, so `hasGoGlyphPair()` discarded `G` and saw only `O`; `detectActiveGo()` therefore returned null even though GO was visibly enabled.
- With the wider collection margin, the complete GO component is retained, both `G` and `O` pass the existing glyph-pair proof, and the existing >=79% physical-screen center lock still excludes the central drone.

Preserved unchanged: single-tap Pikmin filter, selection-grid geometry, sticky Primary/F1-F3 state machine, loading behavior, GO non-idempotency, BUSY/COMPLETE vetoes, fruit classification, Green X ACK, screenshot error=3 throttle, 24% list swipe, and 3 s settle.

---

# 0.4.8-alpha30 — persistent loading GO-watch + fruit color filter

Key changes:
- Selection loading no longer fails after a fixed 12–15s timeout. Concrete placeholder rings gate loading; the broad heuristic alone cannot keep the state stuck when placeholders=0. While loading, Cancel/Fallback remain locked and the controller keeps watching the strict bottom-right GO.
- Optional fruit group filter: 全拿, 青(青蘋果), 黃(檸檬/柳橙), 紅(蘋果/桃子), 藍(梅子). Label-first grouping with visual color fallback; BUSY/COMPLETE safety still runs before fruit filtering.
- Existing single-tap Pikmin color filter, BUSY preflight, GO safety, sticky fallback, list settle, and Green-X recovery remain intact.

This build addresses two real-phone regressions without rewriting the stable navigation/transport flow:

- **Pikmin colour filter is now single-tap/idempotency-safe.** A proven colour chip is never blindly tapped twice. The app first checks the selected-chip drop shadow; after one tap it accepts selected-shadow, row-dim, or roster-loading as ACK. If visual ACK stays ambiguous it continues to selection/GO reconciliation rather than toggling the chip again.
- **GO has an independent hard safety lock.** The detector only accepts a large orange/red disc in the absolute bottom-right with two internal white glyph blobs (G/O), and the controller rejects any GO point left of 79% W or above 80% H. The centre drone cannot pass this gate.
- **Fruit gets an OCR-free second-frame BUSY preflight before tap.** A fresh screenshot re-checks BUSY/COMPLETE cards, clipped-card evidence, and the grey+red progress rail. Duration text above a candidate (17分 / 2小時 / 158日18小時 etc.) is now accepted on any row with a tight vertical association, not only at the bottom edge.

This release fixes a concrete false-AVAILABLE case seen on a 912×2048 expedition screenshot: the middle-bottom green apple sits in a BUSY card whose header is `12分`. The previous detector only inspected the grey/red progress rail after OCR had recognized the duration text. If ML Kit missed or fragmented `12分`, the clearly visible BUSY rail was ignored.

New behavior:
- Before OCR-dependent BUSY checks, each fruit candidate is checked for a same-column progress rail directly above it.
- The rail requires a long neutral-grey track plus a warm-red/pink filled prefix on the same scanline.
- It is candidate-local and distance-bounded, so a BUSY card in another row/column cannot broadly suppress unrelated cargo.
- OCR duration (`12分`, `17分`, `2小時`, etc.) remains a secondary signal, not the gate that unlocks rail detection.
- Existing card-first BUSY/COMPLETE, clipped-card guards, fruit/seedling logic, sticky fallback, loading-safe GO, Green X, 24% list swipe and 3 s list settle are preserved.

Diagnostic when this guard fires:

```
SKIP[BUSY_PROGRESS_RAIL] object @(x,y) col=N
```

## Changes

- **搬運次數改為直接輸入數字**：不再固定 1 / 5 / 10 / 20 下拉選單。輸入 `0` 代表無限循環，可輸入 1～1,000,000。舊版 runMode 設定會自動遷移。
- **中間列 BUSY 水果再加一層語意 guard**：若同欄水果上方出現 `17分`、`35分鐘`、`2小時`、`158日18小時` 等剩餘時間，且時間下方同時看到 BUSY 卡特有的長灰色 progress rail，直接判定為 BUSY，不依賴淡粉紅框一定被手機渲染出來。
- **下方裁切卡仍保留 duration-only fallback**；上方只露下半張卡則仍以既有 pastel border / side rails / card geometry 為主，不用時間亂猜。
- 花苗、sticky fallback、GO、Green X、24% list swipe、3000ms settle、loading-safe selection 都維持 alpha26。

### New diagnostics

```text
SKIP[BUSY_DURATION_PROGRESS] object @(x,y) col=n
SKIP[BUSY_DURATION_HEADER] object @(x,y) col=n
SKIP[STATUS_CLIPPED_LOWER] object @(x,y) col=n
```

`BUSY_DURATION_PROGRESS` 是這版新增的主要保險：**duration 在上 + progress rail 在上方卡頭 = 不點**。

# 0.4.4-alpha26 — generic BUSY duration + loading-safe GO gate

This build is a minimal delta from 0.4.3-alpha25. Existing expedition scan, CTA, fallback colours, sticky cursor, strict GO, Green-X, 24% list swipe and 3-second list settle are preserved.

## BUSY duration semantics

For a **bottom-clipped carried card whose upper half is visible**, the semantic guard no longer assumes the header must be `N日 N小時`. Same-column duration-like OCR **above** the fruit can veto the candidate, including:

- `158日18小時`
- `2小時`
- `35分鐘`
- `7分`
- `45秒`

This is deliberately directional. AVAILABLE travel-duration text appears below normal cargo and is not used as BUSY evidence.

For a **top-clipped card where only the lower half is visible**, the duration shortcut is not used; the existing BUSY/COMPLETE pastel border + side-rail / partial-card geometry remains authoritative.

Diagnostic: `SKIP[BUSY_DURATION_HEADER]`.

## Selection loading: taps allowed, fallback locked

Some phones show hollow grey placeholder rings after a colour chip is tapped while Pikmin artwork is still loading. Loading is **not** evidence that the colour is exhausted. alpha26 therefore changes the state machine:

```text
filter tap
→ placeholder rings may still be visible
→ geometric Pikmin-slot taps are still allowed
→ watch strict bottom-right GO
→ while loading is visible: NO Cancel / NO Fallback
→ after loading clears: require two stable non-loading frames
→ if GO lights at any point: commit immediately
```

If queued taps were issued during loading but the page settles at `0/N` with GO still off, the app retries the **same plan once** on the stable grid before considering fallback. It does not cancel simply because loading was observed.

There is also a minimum 3-second no-GO settle before insufficiency may trigger fallback. If loading persists for 12 seconds, the app fails closed without pressing Cancel or switching colour.

If selected/maximum text is temporarily unreadable but the strict GO detector is clearly enabled, GO itself is treated as authoritative proof of a legal non-zero team.

## Zero-selected fallback

If a plan is genuinely insufficient with `selected=0`, GO absent, loading cleared, and the selection page still visible, there is nothing to clear. The existing zero-select in-place transition to the next fallback colour remains in use. Live selections (`selected>0`) still require a real reset before changing plan.

# 0.4.4-alpha26 — bottom-clipped BUSY hardening

This build is based directly on 0.4.2-alpha24. Selection fallback, GO, Green-X, 24% list swipe, 3s settle, exclusion-first fruit OCR, and sticky plan cursor are unchanged.

## Fix: carried fruit at the bottom edge

Some phones still tapped a fruit that was already being carried when its BUSY card was clipped by the bottom of the screenshot. The old lower-edge guard depended too much on the pale side rails remaining visible all the way toward the physical screen bottom. Floating controls / system navigation / colour management can hide or shift those rails.

alpha25 adds two independent protections:

1. **BUSY return-time header guard** — a same-column header such as `158日18小時` above a lower-edge candidate is treated as BUSY-card evidence even if the pastel frame is partially obscured. Normal available travel-time text is below the object, so it does not satisfy this geometry.
2. **Two-frame AVAILABLE confirmation for edge candidates** — cargo in the upper/lower risk zones must remain AVAILABLE on a second fresh screenshot 850 ms later before it may be tapped. BUSY/COMPLETE evidence on either frame vetoes the tap. Normal middle-of-list cargo keeps the single-pass path.

New diagnostics include:

```text
SKIP[BUSY_DURATION_HEADER] object @(...) col=...
SKIP[STATUS_CLIPPED_LOWER] object @(...) col=...
CARGO EDGE VERIFY ⏳ ...
CARGO EDGE VERIFY REJECTED ✅ ...
CARGO EDGE VERIFY STABLE ✅ ...
```

- Fixes repeated Android Accessibility `takeScreenshot error=3` by using an adaptive screenshot interval and a dedicated transient throttle retry path.
- Error 3 no longer consumes the normal 3 hard screenshot retries, so Green-X ACK and other stages do not abort just because the phone rate-limits screenshots.
- Copy Log buffer increased to ~250k characters so a longer run is less likely to lose the beginning of the session.
- Selection fallback / sticky plan / cargo / GO behavior is otherwise unchanged from 0.4.1-alpha23.

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