# Agent Handoff

## RTK14 full-roster handoff (2026-08-03)

- Source worktree: `/tmp/opensamguk-possession-five-stats`, branch `codex/fix-possession-five-stats`.
- Docker worktree: `/tmp/opensamguk-docker-scenario-five-stats`, branch `codex/v26-effective-scenario-mount`, PR #25.
- Implementation and all known PR-finding remediation are green. The latest review fixes isolate retained `general_ex` build RNG, reconcile denied possession reservations on claim-screen reload, and make V26 consume the same external-over-classpath effective scenario as fresh seeding. Review artifact: `docs/superpowers/reviews/2026-08-03-rtk14-full-roster-five-stats-review.md`.
- Private GitHub Actions input `RTK14_STATS_JSON_B64` is registered; never print or commit decoded data.
- Docker PR #24 merged after three clean mention reviews and its deployment workflow succeeded. Source PR #356 exact-HEAD review at `2d354a6a9ee8b14104fca60bfb7f00fd96e1cf72` found retained-extension RNG, denied reload cleanup, and effective V26 scenario-source gaps; all three are local and ready to commit. Docker PR #25 carries the canonical per-server game-api mount.
- Next exact sequence: commit/push source fixes, restart three sequential source reviews; complete Docker PR #25 three reviews; merge both, reset/reseed only target `pep`, verify domain health, engine clock, DB stat diversity/source metadata, lifecycle activation, and five-stat possession UI.
- Latest evidence: backend `BUILD SUCCESSFUL in 15m 59s`, XML 4,404 tests with zero failures/errors and one skip, game 251/251, both frontend typechecks, Agent OS/strict 0/0. Focused: importer 20/20, ScenarioJson 15/15, resolver 4/4, V26 5/5, SeedBootstrap 3/3, possession 21/21, V36 IT 3/3, CharacterClaim 7/7. `$os-verify` stack start was intentionally not bypassed after missing `JWT_SECRET`; dummy-value Compose config checks passed without reading secrets. Corrected source-JSON two-pass remains 30/30 byte-identical.

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
