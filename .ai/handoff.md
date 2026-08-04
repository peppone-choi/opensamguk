# Agent Handoff

## RTK14 full-roster handoff (2026-08-04)

- Source worktree: `/tmp/opensamguk-possession-five-stats`, branch `codex/fix-possession-five-stats`.
- Docker worktree: `/tmp/opensamguk-docker-scenario-five-stats`, branch `codex/v26-effective-scenario-mount`, PR #25.
- Exact prior source HEAD `725195fea29b3434cc358e3d262c6c440830dab7` was reviewed with a P1 released-V26 forward-repair finding. This branch no longer modifies V26 at all: `V26__npc_lifecycle_phase_units.kt` and `V26NpcLifecycleMigrationTest.kt` are reverted byte-for-byte to `origin/main`. All RTK14 lifecycle repair now lives in one new world-scoped migration, `V38__rtk14_npc_lifecycle_repair.kt` (test: `V38Rtk14NpcLifecycleRepairMigrationTest.kt`), which runs on every world. It uses external-over-classpath effective scenarios, `name[2]`/`nation[4]` action identity, excludes `rtk14Added`, strictly validates event shape, splits verified grouped events while preserving unrelated entries, and fails closed on ambiguity.
- Why V38 rather than an extended V26: a database that already recorded V26 in `flyway_schema_history` never re-runs it, so an extension could not reach upgraded worlds; and on a fresh database Flyway runs before `ScenarioSeedRunner` (an `ApplicationRunner`), so `world_state` is empty and V26 returns immediately, making the extension unreachable on new worlds too. Putting the whole repair in V38, which no world has recorded yet, is what makes already-migrated and freshly seeded worlds converge on the same final state.
- Migration numbering: the claim-request migration was renumbered `V36__general_owner_claim_request.sql` → `V37__general_owner_claim_request.sql`, because `origin/main` already ships `V36__diplomacy_casualties.sql` and two V36s make Flyway fail with a duplicate version.
- The CodeRabbit ambiguity finding is now closed inside V38 (duplicate future-appearance identities fail closed) rather than by extending V26. The remediation also validates importer `appearanceYear <= deathYear` and replaces possession's `takeIf` conditional-delete side effect with an explicit branch. The earlier retained-`general_ex` RNG, denied-reload reconciliation, and effective-scenario fixes remain present. The remediation remains uncommitted in this worktree.
- Private GitHub Actions secret is registered; never print or commit decoded source data. It is a secret, not an Actions input.
- Docker PR #25 changes the scenario mount contract only: `COMPOSE_HOST_DIR` supplies the daemon-visible default and the focused test renders Compose JSON instead of scanning indentation. This is candidate-branch validation, not a completed deployment.
- Next exact sequence: commit/push source fixes; give source PR #356 and Docker PR #25 three new sequential mention reviews on their new exact SHA; remediate any findings; then merge, deploy, reset/reseed only target `pep`, and verify domain health, engine clock, DB stat diversity/source metadata, lifecycle activation, and five-stat possession UI. None of those release steps is complete.
- Latest focused evidence: importer 21, possession 21, and Docker's focused contract test are green; the deep repair-migration re-review is CLEARED. The old V26 focused count no longer applies to this branch, since V26 and its test are reverted to `origin/main`; that coverage moved into `V38Rtk14NpcLifecycleRepairMigrationTest` (9 cases — external-only scenario resolution, external-over-classpath precedence, per-nation deferred identity, duplicate future-appearance fail-closed, missing-scenario fail-closed, plus a new malformed-external-override rollback case), which was not re-run in this documentation pass. Earlier broad backend evidence predates this working-tree remediation and is not a final full gate for it.
- Documentation tooling note: a broad Docker-worktree keyword search was blocked by the repository secret-protection hook because its exclusion glob named a protected secret path. No secret was read; direct inspection of the tracked Compose/test diff succeeded. This is an isolated documentation-tooling limitation, not a product-test result.

- Updated at: 2026-07-21
- State: CQRS B1 complete on main (127+residual reads, 128, 129)

## Landed

| Ticket | PR | Notes |
|--------|-----|-------|
| OPENSAM-127 | #302 + residual-reads | all GWT-named read/Redis cohorts world-scoped |
| OPENSAM-128 | #303 | flush contract + handoff |
| OPENSAM-129 | #304 | two-world isolation IT |

## Next

- B2 S3 generation/fence/CAS (OPENSAM-130+)
- Optional OPENSAM-10 tournament fight()
- Activation/cutover still blocked (prod EC2 + live capacity)

## Do not

- prod deploy/cutover/W3 activation without gates
- co-widen JdbcFlushExecutor without sequential handoff

## B2 handoff (2026-07-21)
- B2 S3-T1..T3 landed or landing on main (130–132).
- Next CQRS priority: S4 inbox authority / outbox (plan ARCH-S4), not activation.

## B2 complete evidence close (2026-07-21)
- Reviews 131/132 cleared; TurnRunServiceFlushRecoveryTest on real runTick/intake; SCRATCH op131/op132/sweep archived in implementer session.
