# Compact map tooltip implementation plan

## Goal

Restore a compact hover tooltip in both map consumers without discarding the
canonical spatial province → jurisdiction → commandery path introduced by the
three-layer map contract.

## Product contract

- The hover tooltip has at most two visual rows: the existing place heading and
  one compact metadata row.
- The metadata row keeps the complete hierarchy path when canonical hierarchy
  data is available.
- When all ownership levels agree, the owner shown beside that path is the
  explicitly resolved owner for the active administrative layer.
- When ownership levels differ, the same metadata row compactly names the
  spatial, jurisdiction, and commandery owners. This preserves the approved
  requirement that conflicts remain visible in tooltips without restoring the
  verbose warning stack.
- Separate province/jurisdiction/commandery rows and mismatch warning prose do
  not appear in the default hover tooltip. The underlying ownership and mismatch
  fields remain available to other consumers.
- In-game and lobby maps render the same information contract.

## Steps

1. Change the in-game and lobby interaction tests to require one compact metadata
   row and reject verbose ownership/mismatch rows.
2. Add a shared formatter for the compact metadata text and use it from both map
   consumers.
3. Run shared, in-game, and gateway focused tests, then both frontend typechecks,
   test suites, and production builds.
4. Perform independent adversarial review, record it under
   `docs/superpowers/reviews/`, and run the strict repository check.
5. Open a small PR, pass CI/review, merge, deploy/promote, and verify the tooltip
   in the real browser.

## Out of scope

- Administrative ownership calculation or map coloring.
- Map geometry, labels, icons, supply, personal mail, or historical name data.
- A new click/selection details panel; this slice only removes detail overload
  from the transient hover surface.
