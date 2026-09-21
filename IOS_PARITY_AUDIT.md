# Android 0.3.9-alpha21 reliability note

This release is a minimal delta from alpha20 and targets three field failures shown in real screenshots.

## 1. Bottom-clipped carried fruit

alpha20 already recovered top-clipped BUSY/COMPLETE cards. alpha21 adds the symmetric lower-edge case: a carried card whose bottom is outside the screenshot may expose only its top pastel border and one or two side rails. The recovered blocked rectangle starts at that top status border and extends only to the screenshot bottom, so AVAILABLE rows above remain untouched.

## 2. GO versus drone

GO is treated as a non-idempotent action. The enabled detector now requires:

- physical screenshot bottom-right position (not only active-content normalized position),
- large orange/red/peach component geometry, and
- white glyph evidence inside the component.

This excludes the centre-bottom drone/control even when it lights at the same time.

## 3. Selection fallback semantics

The game can enable GO with fewer Pikmin than the configured target or displayed maximum. Therefore an enabled GO is now authoritative dispatchability evidence as long as at least one Pikmin is selected.

The count model remains:

`effectiveRequired = min(configuredCount, liveMaximum)`

but it is used only after bounded GO reconcile when GO is still absent:

- GO enabled + selected > 0 => commit, even if selected < effectiveRequired
- GO absent + selected < effectiveRequired => fallback
- GO absent + selected >= effectiveRequired => GO/UI recovery problem, not fallback

Fallback reset, no-stacking guarantees, busy-toast diagnostic behavior, and one-shot GO commit safety are unchanged. Fallback UI choices are limited to 岩 / 紫 / 粉紅 / 白.
