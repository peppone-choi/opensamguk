# OPENSAM-124 lifecycle review

Scope: .ai/, .codex/, .superpowers/, app/, docs/, tools/

Verdict: cleared

- `REJECTED_BEFORE_RING` is terminal and blocks every suffix child; focused two-child coverage matches PHP stop-at-first-failure semantics.
- Synchronized lifecycle operations make expected-`stageVersion` guard and replacement atomic in memory; concurrent same-version coverage proves exactly one transition succeeds.
- Stage B applies below-floor `killturn` only through `ChangeRecorder` plus the in-memory world. Above-floor and `npc >= 2` paths are `NOOP`.
- `opensamguk.engine.nationbulk` is included in `DaemonWriteGuard` and its required-package assertion.
- Eight focused lifecycle tests and the daemon no-`EntityManager` architecture test pass. Durable activation remains blocked on OPENSAM-43/W3 world scope, durable CAS, and fenced flush binding.

Independent reviewers: `review_op124_lifecycle` and the supplemental full-worktree `final_two_work_review` (`fable-deep-reasoner`), 2026-07-19.
