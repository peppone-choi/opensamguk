---
name: os-checkpoint
description: Use when saving opensamguk task context before compaction, reset, handoff, agent change, or a deliberate pause.
---

# OS Checkpoint

Announce this workflow, then read `.claude/commands/os-checkpoint.md` and `docs/agent/context-strategy.md` completely. Treat the current reason as `$ARGUMENTS`. Update only current facts in the required `.ai/` state files, preserve other agents' ownership, distinguish observed verification from unexecuted checks, and avoid verbose logs.
