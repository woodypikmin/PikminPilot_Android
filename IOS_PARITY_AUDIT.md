# Android 0.3.5-alpha17 navigation safety note

This hotfix prevents the top-level `花苗` navigation tab from ever becoming an expedition cargo target on devices whose bottom sheet is vertically shifted. It adds a dynamic OCR-derived navigation boundary and a fail-closed guard. Existing alpha16 swipe/settle timing and alpha15 filter/Green-X logic are otherwise retained.
