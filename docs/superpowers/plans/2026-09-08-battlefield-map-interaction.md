# Battlefield map interaction

Preserve the approved map's neutral visual hierarchy. Add a distinct battlefield target identity, never a synthetic city. Project approximate source latitude/longitude using the loaded terrain artifact's persisted projection; invalid or missing projections withhold markers. The marker and accessible target button open a compact panel showing current presence and server eligibility.

Use GET /api/battlefields only for the active live Han map. Reserve che_전장이동 through the existing intake API with exact catalogHash and positionRevision; AVAILABLE means queued, not movement resolved. Disable pending requests and unavailable server decisions; preserve denied reasons. Refetch eligibility after intake and ordinary map refresh. No enemy occupants are disclosed.

Test projection fail-closed behavior and panel entry/exit payloads, queued/denied states, disabled eligibility, and current-site display. Run relevant map tests and TypeScript checks.

## Validation

- New panel/projection tests first failed for missing implementation, then passed.
- Existing map suites: 119/120 initially passed; the political-cache regression exposed a changing render dependency. Keeping projected targets in a ref restored cache reuse, and the full affected canvas suite passed.
- Final affected canvas/panel/projection suite passed all 43 tests; the two additional live MapViewer tests passed separately, covering current-site highlight exclusion and stale-server response rejection.
- `pnpm --dir web/game typecheck` passed. `git diff --check` passed.
- Eligibility refetch preserves intake status text. Server changes reject a command before transmission; queued intake never changes current position optimistically.
