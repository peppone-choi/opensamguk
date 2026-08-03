# Review: RTK14 full roster, lifecycle, and five-stat surfaces

Scope: .github/workflows/ app/ infra/ logic/ tools/ web/
Verdict: cleared

## Findings and resolution

- The first review found exhausted duplicate-name candidates falling back to implicit politics/charm 50/50. The builder now requires exact runtime-identity collision overrides and fails closed when any legacy-only row lacks reviewed values.
- The first review found matched lifecycle changes could shift later legacy `InitScenario` draws. Tuple index 24 now records pre-enrichment activity, old-active rows phantom-consume legacy build draws, and newly active or appended rows use `InitScenarioRtk14`.
- The second review found 25-slot legacy-only rows were incorrectly receiving null `rtk14_*` provenance. Metadata now requires a non-null RTK14 officer number, with an end-to-end negative regression.
- PR review found the shared `general_neutral` tuple was decoded with direct-action offsets. Neutral deferred officers now use the importer layout, including birth/death at indices 9/10, and malformed nullable year scalars fail closed.
- PR review found V26 could lose an underage officer after RTK14 enrichment rewrote the tuple birth year. The migration keeps exact birth matching first and uses a unique source-provenanced identity fallback only for the pending pre-V26 upgrade case.
- PR review found NPC possession redirected after intake acknowledgement instead of daemon completion. The UI now waits for the terminal command result and preserves the selection screen on denial, timeout, or missing request ID.
- PR review found the tuple-24 RNG marker was decoded ad hoc and RTK14 additions outside `general` could be silently dropped. The marker is now typed, and `general_ex`/`general_neutral` additions fail closed to preserve initialization order.
- PR review also hardened archive-only scenario exclusion and secret-step tracing checks. Claims that legacy politics/charm defaults should be removed and that the mocked `GameChrome` test leaked API calls were rejected after verifying the persisted V16 defaults and complete API-child mocks.
- A later exact-HEAD review found V26 still scheduled source-enriched rows at `birth + 14` and the possession retry path mishandled an idempotent success without a request id. V26 now uses `appearanceYear ?: birth + adultAge`; `CharacterClaim` completes the documented `AlreadyOwnedBySelf` success without polling while preserving every `result:false` denial.
- The next exact-HEAD review found that V26's database selector still excluded a stored-adult row whose reviewed RTK tuple moved appearance into the future. V26 now unions the original underage cohort with adult rows only when tuple index 24 proves they were legacy-active, explicit appearance is future, and identity matching is safe; ambiguous same-name adult rows remain untouched.
- The following exact-HEAD review found `seedGenerals(false)` dropped workbook-enriched officers that remained in `general_ex`. Source-provenanced rows are now selected by non-null officer number independently of the legacy extension flag, while ordinary legacy extensions remain filtered; a 1,000-ID split-section regression passes.
- The same review proved that account ownership was persisted before daemon completion, so a retry could turn terminal denial or timeout into a no-request-id false success. V36 stores the original `claim_request_id`; retries and reloads reuse that request without republishing, matching terminal denial conditionally releases only its provisional reservation, and correlated ownership is finalized only at `npc_state=1` with matching `general.user_id`. Pre-V36 null-id finalized ownership remains compatible only outside the claimable `npc_state=2` pool.
- The complete historical review feed also exposed a deploy-only conflict between the source roster's fixed 678-row test and pre-build RTK14 materialization. The workflow now runs the source JVM gate first, then materializes the 1,000-row roster for image builds; static ordering and source-number exact-once contracts cover both phases.

## Evidence

- Real workbook: 1,000 source rows represented exactly once in each of 15 populated runtime scenarios; 15 settings-only scenarios preserved.
- Reviewed runtime-only data: 351/351 legacy-only rows covered, 38/38 exact collision overrides exercised, zero unresolved or unused overrides.
- Lifecycle/RNG: old-inactive to active, old-active to inactive, inactive to inactive, and active to active quadrants audited; focused importer integration suite passed 19 tests with zero failures/errors.
- Builder: 18 Python tests passed against the private workbook; two consecutive real-workbook builds produced 30/30 byte-identical JSON files with zero unresolved names.
- UI/API: game 251 tests and TypeScript typecheck passed; gateway typecheck passed. Full game-api passed 441/441, V36 repository mapping/conditional cleanup passed 3/3 against real PostgreSQL, CharacterClaim passed 7/7, and ScenarioJson passed 15/15. The last fully green `$os-verify` backend gate completed with `BUILD SUCCESSFUL in 14m 59s` and 4,379 XML tests with zero failures/errors and one Docker-dependent skip. The latest broad rerun's sole failure was an unchanged V5 Testcontainers PostgreSQL socket timeout; an isolated rerun passed 3/3 with Flyway V1–V5, so the environment transient is documented rather than masked.
- Deployment: workflow parses, secret materialization is fail-closed and non-logging, Docker scenario mount is read-only, focused deployer contract test and both worktree diff checks passed.
- No workbook, generated scenario, decoded source JSON, credential, or secret is tracked.
