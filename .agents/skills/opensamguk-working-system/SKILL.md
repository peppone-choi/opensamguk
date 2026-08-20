---
name: opensamguk-working-system
description: Use when starting non-trivial work in the opensamguk repository, especially implementation, debugging, parity, infrastructure, or review tasks.
---

# OpenSamguk Working System

Read these sources completely before acting:

1. `AGENTS.md`
2. `CLAUDE.md`
3. `.ai/task.md`
4. `.ai/decisions.md`
5. `docs/agent/project-overview.md`
6. `docs/agent/README.md`
7. `docs/superpowers/WORKING_SYSTEM.md`

Route only to the additional documents required by `docs/agent/README.md`. Follow the latest approved ADR/spec and current implementation. Preserve ownership, human approval gates, deterministic replay, frozen regressions, one-daemon-write, and executed-versus-unexecuted verification reporting. PHP and hwe are optional historical/reference inputs under ADR-LITE-042. Use the matching `$os-*` skill as the workflow entry point.
