# Map Visual Containment Design

## Problem

The current Han canvas clips each city building, flag, state badge, selection treatment, and label to the exact county path. That guarantees no painted pixel crosses a county boundary, but it destroys the visual instead of choosing a safe placement and size. The label font is also calculated in backing pixels, so its CSS size shrinks as DPR rises, and a label that misses the county by one sampled pixel disappears entirely.

Current-data diagnostics make the placement problem concrete:

- 404 of 998 city-linked province seats are on a boundary cell;
- median seat clearance is one cell, while median best in-province clearance is two cells;
- linked provinces range from 8 to 489 cells and include narrow shapes, so one fixed marker size cannot work at every zoom.

## Decisions

1. Province identity remains authoritative. Visual relocation never changes political ownership, command targets, adjacency, or the canonical city coordinate.
2. Each province receives a deterministic interior visual anchor. It maximizes distance from the province boundary, then resolves ties by distance to the canonical seat and row/column order.
3. Detailed building markers use CSS sizes `16`, `24`, `32`, and `48` pixels. DPR changes backing pixels only; it never changes CSS size or LOD.
4. The renderer chooses the largest requested marker tier whose visible building and attached overlays fit in the same province. It does not use a province clip to hide overflow.
5. Before a 16px detailed marker can fit, the overview uses a small bordered anchor glyph. This glyph communicates a city location without pretending the detailed sprite is legible.
6. County labels use the canonical `provinceRecords[].displayName`, not long source annotations from runtime city names. Font size is `11–14 CSS px` multiplied by DPR for the canvas backing store.
7. County labels are LOD-controlled. A label is drawn only when its measured box fits the same province. Hover and selection keep the complete canonical name available through the existing county tooltip when an inline label cannot fit.
8. Hitboxes remain restricted to the marker's canonical province and follow the actually drawn visual tier.
9. Building pixels, flags, state badges, capital badges, selection treatments, and labels must all be fully contained. Color alone is not the only signal: overview glyphs and flags retain dark/light outlines.

## Acceptance criteria

- No `context.clip(provincePath)` is used for city visuals.
- Boundary seats are displayed from deterministic interior anchors without changing ownership binding.
- Marker tiers are exactly 16/24/32/48 CSS px at DPR 1/1.5/2/3, with nearest-neighbor rendering.
- Label CSS size is invariant across DPR 1/1.5/2/3 and never below 11px when drawn.
- A marker or label that cannot fit is deferred or replaced by the overview glyph; it is never partially painted.
- Existing click, touch, keyboard, hover, current-city, and selected-city behavior remains deterministic.
- Unit, interaction, typecheck, production build, and browser screenshot/smoke checks pass.

