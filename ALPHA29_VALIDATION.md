# Alpha29 fixture validation

Validation was run against screenshots already supplied during debugging.

## Filter selected-state fixture

`2a903a95-49e7-4370-b3a7-ee52ce72690c.png` (loading roster, pink selected): selected-shadow score ≈ **0.50**.

`9c331f97-1fa2-4919-9944-4c79f58cec59.png` (rock selected): selected-shadow score ≈ **0.50**.

The alpha29 threshold is 0.38. This lets the controller avoid tapping an already-selected chip, and the controller never blindly issues a second filter tap.

## GO fixtures

- `e88327b9-a11f-48e7-b0fc-5e4cefce00a2.png`: GO centre ≈ (0.841 W, 0.882 H), inside hard safe zone.
- `9c331f97-1fa2-4919-9944-4c79f58cec59.png`: GO centre ≈ (0.841 W, 0.897 H), inside hard safe zone.
- Centre drone in the first fixture is around x ≈ 0.51 W, therefore fails the absolute x >= 0.79 W controller safety gate even if another detector were to return it.

## BUSY progress fixture

`06e30590-1235-474b-bd44-88277702ad2a.png`, the previously missed centre-bottom **12分** green apple: candidate centre ≈ (521, 1941); the broadened pixel progress detector finds a rail around y ≈ 1840 with a neutral run ≈ 210 px and a warm filled prefix. This is a BUSY veto before cargo tap.
