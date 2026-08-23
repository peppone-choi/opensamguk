---
name: opensamguk-php-oracle
description: Use when an explicitly historical opensamguk regression or legacy comparison requires evidence from devsam/core PHP or hwe/ts; never as a prerequisite for new product work.
---

# OpenSamguk opt-in historical PHP comparison

Read `AGENTS.md`, `CLAUDE.md`, and `docs/superpowers/WORKING_SYSTEM.md` completely.

This is an opt-in historical comparison skill. Under ADR-LITE-042, PHP and hwe are references, not product authority. For every claim about the selected legacy behavior:

1. Locate `legacy/devsam-core` source and cite path plus line range. Use it to establish what PHP did, not what new opensamguk behavior must do.
2. Use `hwe/ts/` as optional frontend-flow reference and `legacy/devsam-core2026` as secondary structure guidance. Neither overrides an approved ADR/spec or current product behavior.
3. Map RNG draws, rounding, Korean logs, side-effect order, insertion order, and flush deltas.
4. Capture numeric or log goldens only through `tools/php-golden/`; never invent or weaken a fixture.
5. For current live/UI bugs, start from current runtime evidence with `webapp-testing`, systematic debugging, and `loop-engineering`. Add legacy evidence only if the task explicitly requests it. If required evidence is unavailable, report `채점대기` or blocked instead of shipping.
