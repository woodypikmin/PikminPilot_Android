# Alpha41 validation

Baseline: `0.4.8-alpha40` -> `0.4.8-alpha41` (`versionCode 40`).

## User-visible bug

After the carrying-page **green X** was tapped successfully, Pikmin Bloom could finish transitioning to the Expedition list a few hundred milliseconds later. The list has its own **white-disc / black-X** close control at almost the same bottom-left coordinates. Alpha40 could authorize retry from an earlier still-carrying frame and then land tap #2 on that list X.

## Fix

- Destination selected-tab detector no longer assumes the tab row is only at 9..22%H. It searches the variable Expedition sheet range up to 56%H while retaining the same teal-pill geometry and white-glyph evidence.
- Green-X retry has a 650ms fast / 850ms normal transition-settle floor after each tap. A single early frame with the Green X still visible cannot immediately authorize tap #2.
- Retry requires carrying-X persistence across at least two verification frames after the tap.
- Every retry tap gets a last-moment fresh screenshot guard. Destination proof wins.
- A high-white / low-green circular control at the previous Green-X anchor is a **hard retry veto**. It can never authorize a carrying-X tap.
- No stale-coordinate retry is allowed.

## Supplied screenshot fixture check

On the two 912x2046 screenshots supplied with this bug report, using the same ~55px anchor neighborhood around the bottom-left X center:

- carrying Green X: green fraction ~0.947, white fraction ~0.022
- Expedition list white X: green fraction ~0.001, white fraction ~0.967

The alpha41 white-list-X veto (`white >= 0.72 && green <= 0.10`) cleanly separates these two examples with wide margin.

The expanded selected-Expedition pill search finds the supplied list pill near `(590, 931)` (~45.5%H) and does not find a pill on the carrying-page screenshot.

## Expected log

Successful delayed transition:

```text
GREEN X TAP #1 ...
GREEN-X ACK • carrying X still visible but transition settle window active ... • NO RETRY YET
GREEN-X ACK DESTINATION FAST ✅ • expedition tab pill @(...)
```

If the list white X appears before destination OCR/geometry is otherwise proven:

```text
GREEN-X ACK LIST-X VETO 🛑 • white-disc control at old Green-X anchor ... • retry forbidden
```

A second Green-X tap is allowed only after the Green X persists past the settle window **and** the final current-frame retry guard still proves carrying-X state.

No changes were made to GO, BUSY/COMPLETE, fruit/seedling OCR/classification, Pikmin filter/grid, fallback cursor, loading handling, list swipe timing, or screenshot throttle.
