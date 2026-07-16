---
name: loop-engineering
description: Use when changing opensamguk prompts, agents, hooks, skills, automation, AI settings, or any workflow whose quality must be measured before adoption.
---

# Loop Engineering

Read `docs/superpowers/LOOP_ENGINEERING.md` completely and execute it as the canonical procedure.

Freeze the evaluation contract before tuning. Record one baseline, one hypothesis, one change, one remeasurement, and one adopt-or-revert decision at a time. Do not change tests or scoring to make a candidate pass. Keep implementation and independent criticism separate. Load only the task-relevant loop artifacts under `docs/loops/`.
