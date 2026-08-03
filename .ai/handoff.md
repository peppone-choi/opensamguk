# Agent Handoff

## RTK14 full-roster handoff (2026-08-03)

- Source worktree: `/tmp/opensamguk-possession-five-stats`, branch `codex/fix-possession-five-stats`.
- Docker worktree: `/tmp/opensamguk-docker-scenario-five-stats`, branch `codex/scenario-five-stats`.
- Implementation and all known PR-finding remediation are green. The latest review fixes preserve source-provenanced `general_ex` rows with legacy extensions disabled and durably correlate provisional possession to the original daemon request via V36. Review artifact: `docs/superpowers/reviews/2026-08-03-rtk14-full-roster-five-stats-review.md`.
- Private GitHub Actions input `RTK14_STATS_JSON_B64` is registered; never print or commit decoded data.
- Docker PR #24 merged after three clean mention reviews and its deployment workflow succeeded. Source PR #356 is open; `a275a343861b6c140bc31013d0878ba2d7bb81f1` fixed stored-adult/future-appearance V26 migration, then exact-HEAD review found source `general_ex` filtering and pre-terminal ownership false success. Both later findings and their evidence are local and ready to commit.
- Next exact sequence: commit/push the source fixes, restart three sequential `@codex review` rounds on the new exact SHA and fix findings, merge source, reset/reseed only target `pep`, verify domain health, engine clock, DB stat diversity/source metadata, lifecycle activation, and five-stat possession UI.
- Latest evidence: ScenarioJson 15/15, full game-api 441/441, real PostgreSQL/Flyway V36 repository IT 3/3, CharacterClaim 7/7, game 251/251, both frontend typechecks, Agent OS contract, and strict checker 0/0. The broad backend rerun's only failure was the unchanged V5 Testcontainers PostgreSQL socket read timeout; the isolated V5 rerun passed 3/3 and Flyway V1–V5. The earlier complete broad gate remains `BUILD SUCCESSFUL in 14m 59s`, 4,379 XML tests with zero failures/errors and one skip. The corrected source-JSON two-pass run produced 30/30 byte-identical files and is the valid workbook evidence.

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
