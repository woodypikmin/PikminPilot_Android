# Android 0.3.2-alpha14 iOS parity / Android divergence note

The cargo safety model now follows the iOS `FruitDetector.swift` card-first rule more closely:

- BUSY / COMPLETE / partial cards block only candidates whose inferred centre or OCR label overlaps the reconstructed card.
- A raw horizontal status border elsewhere in the same 3-column lane does **not** block a neighbouring item.
- Android keeps a very narrow exact-recent-dispatch latch only as a backup for a status card that is clipped at the screen edge. The key is OCR-normalized (e.g. `籃` -> `藍`) and the position tolerance is small.

The Pikmin colour filter still uses the iOS purple/pink magenta-anchor relationship, but Android now first locks onto the real colour-chip row. This is necessary because Android screenshots from different devices/accounts can contain magenta Decor/Pikmin artwork lower in the sheet that satisfies the original broad iOS search band.

Green-X handling differs from iOS only at the input/ACK layer:

- detection is constrained to Pikmin Bloom's bottom-left close-control zone;
- retries are anchored to the original X coordinate;
- success is acknowledged by disappearance of that same anchored control on two consecutive frames;
- no repeated full OCR Expedition-list ACK is required after every X tap.

These divergences are Android platform safeguards, not changes to the intended gameplay flow.
