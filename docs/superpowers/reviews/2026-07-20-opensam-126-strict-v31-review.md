# OPENSAM-126 strict V31 independent review

Date: 2026-07-20
Reviewer: independent Codex review agent (`op126_final_clearance`)
Scope: OPENSAM-126 strict V31 first cohort and affected OPENSAM-127/128 runtime scoping across `.ai/`, `.codex/` (user-owned config diff reviewed and excluded), `.env.example`, `app/`, `docker-compose*.yml`, `docs/`, and `infra/`

## Scope

Final adversarial review of the uncommitted `codex/op-126-scoped-schema` stack: the strict V31 first cohort (`nation`, `city`, `general`, `general_turn`, `nation_turn`) and only the affected OPENSAM-127/128 runtime scoping needed to keep that cohort bootable. This review did not authorize or perform a commit, push, merge, deployment, production migration, or tracker mutation.

## Evidence inspected

- Current worktree diff/status and `git diff --check`.
- `V31__world_scope_expand.sql` and `V31WorldScopeExpandMigrationTest`: exact `world_state` plus 30-current-C1 lock inventory, real conflicting-writer wait/fail-closed coverage, pristine/invalid-cardinality handling, and early/late transactional DDL rollback coverage.
- `ScenarioSeedCoordinator`, `ScenarioImporter`, and seed tests: explicit configured `WorldId`, success-path sequence synchronization, non-1 identity propagation, concurrent admission, rollback, and same-ID retry.
- `JdbcFlushExecutor` and persistence tests: cohort-scoped create/update/root-delete/profile predicates, exact affected-row validation, transaction rollback, and wrong-world rejection.
- `FlushPayloadTestFactory` and current callers: `WorldId` is mandatory and explicit; no state-map inference or default remains.
- `ScenarioSeedDisabledTest`: the disabled path returns before constructing/using the coordinator and verifies no JDBC interaction.
- Game API process-world configuration, scoped read facades, `GameApiApplicationTests`, and `CommandControllerIT`: explicit world configuration and scoped fixtures are present.
- `.ai/current-state.md`, `.ai/handoff.md`, inventory/spec comments, and the separate four-line `.codex/config.toml` user diff.
- Current XML aggregation directly supports `ProfileIconFlushIT` 2 tests, game-engine 85 suites / 578 tests / 1 skip, and game-api 57 suites / 402 tests, all with zero failures and errors.

## Prior finding resolution

### MAJOR

- Configured seed identity and sequence retry poisoning: resolved by explicit-ID insert, sequence synchronization only after successful canonical validation, and non-1 plus rollback/retry coverage.
- Silent wrong-world flush deltas: resolved by world predicates and exactly-one affected-row checks for cohort create/update/root-delete/profile writes within the flush transaction.
- Incomplete V31 writer-concurrency and late-DDL rollback evidence: resolved by exact lock inventory, real lock contention, and failures injected both before and after turn-unique changes.
- Disabled-seed test touching JDBC: resolved by an early return and `verifyNoInteractions` coverage.

### MINOR

- Test payload world inference: resolved; `testFlushPayload` requires explicit `WorldId`, and callers provide it.
- Partial profile batch application: resolved; the eligible-first/ineligible-second regression proves the complete transaction rolls back.
- Test/runtime documentation drift: explicit game-api world configuration and scoped fixtures were added, while state/handoff documents keep broader OPENSAM-127/128 and S2-T2/T3 work deferred.

## Deferred scope

This verdict does not clear full OPENSAM-127/128 or full S2-T2/T3. Other C1 tables, request/JWT world authorization, Redis key/consumer scoping, logs/KV/history/messages/auctions, same-local-ID coexistence, second-world admission, cutover, production migration, and deployment remain deferred.

## Non-blocking note

The recorded full-infra 43-suite / 160-test result is historical evidence: the final targeted profile run replaced the current infra XML, so it cannot now be independently re-aggregated. Current XML directly supports ProfileIcon 2, engine 578, and API 402 green tests. Generic Fablize hook failures are isolated in `.ai/handoff.md` and were not used as build/test verdicts.

Verdict: cleared
