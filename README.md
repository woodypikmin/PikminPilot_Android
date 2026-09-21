# 0.4.6-alpha28 — pixel-first BUSY progress-rail guard

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