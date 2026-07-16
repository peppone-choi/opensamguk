---
name: os-debug
description: Use when an opensamguk test, runtime flow, deployment, or UI behavior fails and the root cause is not yet proven.
---

# OS Debug

Announce this workflow, then read `.claude/commands/os-debug.md`, `docs/agent/failure-cases.md`, and the routed debugging documents completely. Treat the current symptom as `$ARGUMENTS`. Use Codex tools to test competing hypotheses before editing. Preserve the canonical stop conditions, never weaken tests or goldens, and require regression evidence after a fix.
