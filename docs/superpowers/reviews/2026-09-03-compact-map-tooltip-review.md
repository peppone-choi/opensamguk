# Compact map tooltip adversarial review

## Scope

- `web/shared/src/HanMapCanvas.tsx`
- `web/shared/src/nationVisual.ts`
- `web/game/components/game/MapViewer.tsx`
- `web/gateway/components/MapPreview.tsx`
- corresponding interaction and formatter tests

## Initial findings

The independent reviewer returned `fix-required` for two correctness risks and
one missing test branch:

1. Removing all three ownership rows also removed the approved guarantee that a
   province/jurisdiction/commandery ownership disagreement remains visible in
   the tooltip.
2. `displayedOwner?.nationName ?? city?.nationName` could show the runtime city
   owner when the selected administrative layer was explicitly unowned or its
   nation display metadata was absent. The lobby and game wrappers could then
   diverge.
3. Wrapper fixtures covered only the mismatch branch, not the normal single-owner
   or explicit-unowned formatter branches.

## Remediation

- Ownership disagreements are now summarized in the single metadata row as
  `공간 / 현 / 군국`; only the repeated rows and explanatory warning prose were
  removed.
- `IsoCountyHover.displayedOwnerNationName` is derived directly from the selected
  administrative owner. When that owner exists, `nationName` and `nationColor`
  no longer fall back independently to the runtime city.
- Formatter tests cover agreement, disagreement, and explicit unowned output.
- A real `HanMapCanvas` interaction test proves an explicit unowned jurisdiction
  reports `미소유` and no political color instead of the runtime city's owner.

## Final verdict

`CLEARED` — no contract or correctness blocker remains in the reviewed diff.

Reviewer verification: 101 related game tests, 10 gateway tests, shared
typecheck, and `git diff --check` passed.

## Remaining non-blocking risk

The disagreement summary intentionally remains a single no-wrap row. Verify in
the deployed browser that unusually long faction names and hover near the right
map edge do not clip; adjust placement in a follow-up only if production evidence
shows overflow.
