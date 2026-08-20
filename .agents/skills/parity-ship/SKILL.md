---
name: parity-ship
description: Use when an explicitly historical frozen-regression parity batch is ready for final gates, PR preparation, approved merge, deployment, and canary verification; never for new product work.
---

# Parity Ship

Read `.claude/skills/parity-ship/SKILL.md` completely and execute the same provider-neutral workflow.

This historical tool is opt-in and is never required for new product work under ADR-LITE-042.

Map Claude role names to the matching `.codex/agents/*.toml` roles. Preserve explicit approval before commit, push, merge, deploy, or production mutation. A `fix-required` verdict blocks shipping. Report every unexecuted gate and production observation as unverified.
