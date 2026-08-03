# Review: RTK14 full roster, lifecycle, and five-stat surfaces

Scope: .github/workflows/ app/ infra/ logic/ tools/ web/
Verdict: cleared

## Findings and resolution

- The first review found exhausted duplicate-name candidates falling back to implicit politics/charm 50/50. The builder now requires exact runtime-identity collision overrides and fails closed when any legacy-only row lacks reviewed values.
- The first review found matched lifecycle changes could shift later legacy `InitScenario` draws. Tuple index 24 now records pre-enrichment activity, old-active rows phantom-consume legacy build draws, and newly active or appended rows use `InitScenarioRtk14`.
- The second review found 25-slot legacy-only rows were incorrectly receiving null `rtk14_*` provenance. Metadata now requires a non-null RTK14 officer number, with an end-to-end negative regression.
- Final independent re-review found no remaining fix-required item.

## Evidence

- Real workbook: 1,000 source rows represented exactly once in each of 15 populated runtime scenarios; 15 settings-only scenarios preserved.
- Reviewed runtime-only data: 351/351 legacy-only rows covered, 38/38 exact collision overrides exercised, zero unresolved or unused overrides.
- Lifecycle/RNG: old-inactive to active, old-active to inactive, inactive to inactive, and active to active quadrants audited; focused importer integration suite passed 19 tests with zero failures/errors.
- Builder: 14 Python tests passed; two consecutive real-workbook builds were byte-identical.
- UI/API: game 245 tests and gateway 78 tests passed with both TypeScript type checks; backend logic, infra, game-engine, and game-api module tests completed with `BUILD SUCCESSFUL`.
- Deployment: workflow parses, secret materialization is fail-closed and non-logging, Docker scenario mount is read-only, focused deployer contract test and both worktree diff checks passed.
- No workbook, generated scenario, decoded source JSON, credential, or secret is tracked.
