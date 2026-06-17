---
name: loop-engineering
description: Use when improving Codex/Claude agent configuration assets or iterating on opensamguk parity/bug gaps with a measured loop. Trigger on "loop-engineering", "루프 엔지니어링", "루프 돌려", agent-behavior tuning, self-grade/auto-apply requests, or score-stagnation/rubric-change work.
---

# loop-engineering adapter for Claude

Read `../../../docs/superpowers/LOOP_ENGINEERING.md` completely before acting.
That file is the shared Claude/Codex source of truth for loop rules.

Claude execution notes:

- Use the available Claude planning/Todo surface for the active loop steps.
- Use the available edit tool for manual edits.
- Use repository search before changing behavior.
- Use deterministic repo gates as graders when possible.
- Use a fresh reviewer agent for non-deterministic rubric work.
- Report in Korean when the user is speaking Korean.
