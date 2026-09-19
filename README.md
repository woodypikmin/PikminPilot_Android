# PikminPilot Android 0.3.5-alpha17

Safety hotfix based on alpha16.

## Main change: dynamic Expedition navigation guard

Different phones place the Pikmin Bloom bottom sheet and the top navigation row at different Y positions. Alpha17 no longer assumes that cargo starts below a fixed `0.16H`. Each list scan OCR-locates the navigation row (`記錄 / 皮克敏 / 花苗 / 探險 / 明信片`) and computes a live `contentTopY`.

- Fruit/seedling candidates above `contentTopY` are never tappable.
- Plain `花苗` remains rejected.
- OCR strings containing more than one `花苗` token are rejected, preventing a glued navigation-tab + cargo label from becoming a false seedling.
- If the navigation guard cannot be proven, Pilot does **not tap and does not swipe**. It waits 3 seconds and retries; after 5 failures it stops safely.
- Alpha16's 24% expedition-list swipe and 3000 ms post-swipe settle are unchanged.

Useful log lines:

```text
NAV-GUARD • proven=true • source=expedition-tab ...
SCAN-DIAG SKIP[NAV_GUARD] ...
SCAN-DIAG SKIP[NAV_GUARD_UNPROVEN] ...
```

Build marker: `BUILD 0.3.5-alpha17`.
