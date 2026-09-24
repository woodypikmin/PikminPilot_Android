# alpha30 validation notes

## Loading bug fixed
Observed log: `placeholders=0` while the broad loading heuristic still returned true, so alpha29 stayed in loading until the hard timeout and threw `selection page still loading; refusing cancel/fallback`.

alpha30 rule:
- concrete placeholder count >= 2 => loading
- a broad loading heuristic is only supporting evidence when at least one concrete placeholder exists
- loading itself never triggers Cancel/Fallback
- no fixed hard loading failure; keep watching strict GO while the run is active

## Fruit groups
Optional UI groups:
- GREEN: 青蘋果
- YELLOW: 檸檬 / 柳橙 / 橘 / 橙
- RED: 蘋果 / 桃子
- BLUE: 梅子

If OCR is damaged, group falls back to dominant saturated hue in the already-detected fruit component. Unknown group is accepted only when `全拿` is selected; when selective filtering is active, unknown-group fruit is skipped conservatively.
