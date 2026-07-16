---
name: os-review
description: Use when performing an independent adversarial review of an opensamguk diff, plan implementation, parity change, or Agent OS change.
---

# OS Review

Announce this workflow, then read `.claude/commands/os-review.md` and `docs/agent/lifecycle-review.md` completely. Treat the requested scope as `$ARGUMENTS`. Use a fresh read-only Codex agent when independence is required. Save non-trivial review evidence under `docs/superpowers/reviews/` with exactly one verdict: `cleared`, `fix-required`, or `quarantined-with-proof`. Never self-clear unresolved findings.
