---
name: parity-ship
description: Use when a reviewed batch of CLOSED opensamguk parity items is ready for final gates, PR preparation, approved merge, deployment, and canary verification.
---

# Parity Ship

Read `.claude/skills/parity-ship/SKILL.md` completely and execute the same provider-neutral workflow.

Map Claude role names to the matching `.codex/agents/*.toml` roles. Preserve explicit approval before commit, push, merge, deploy, or production mutation. A `fix-required` verdict blocks shipping. Report every unexecuted gate and production observation as unverified.
