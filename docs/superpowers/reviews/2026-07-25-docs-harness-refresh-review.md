# Documentation and harness refresh review

Scope: `README.md`; `.claude/HARNESS.md`; `.claude/skills/parity-close/SKILL.md`; `.claude/skills/parity-ship/SKILL.md`; `docs/agent/lifecycle-ops.md`; related Agent OS task metadata

Independent reviewer: `fable-deep-reasoner` (`docs_harness_refresh_review`)

The initial review found six blocking or major issues: an unauthorized intermediate-commit
instruction, inverted immediate-versus-reserved command semantics, incorrect durable command
ordering, an obsolete production procedure, incorrect scenario-seed behavior, and an incomplete
parity verification matrix.

The final review confirmed that the latest worktree:

- classifies immediate daemon commands through `intakeCodes`/`toCommand` and turn-reserved
  `che_*` commands through the reserved ring plus `ReservedTurnHandler`/`CommandRegistry`;
- documents inbox DB commit, best-effort Redis wake, claim/apply, one durable DB transaction,
  and post-commit XACK/publication in the observed order;
- matches the separate shared-stack deployment model and preserves per-server image pins;
- records configured-world admission, `map/<mapName>.json`, and the 94-city seed result;
- keeps commit, push, merge, and production actions behind separate explicit human approval; and
- includes backend XML gating, frontend and changed-scope checks, plus browser observation for UI
  flow changes.

Review-time evidence: `git diff --check`, stale-phrase scanning, and targeted path checks passed.
The Agent OS checker remained red only for the pre-existing, out-of-scope personal model pin in
`.codex/config.toml`.

Verdict: cleared
