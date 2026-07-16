---
name: opensamguk-php-oracle
description: Use when an opensamguk legacy gap, parity mismatch, UI divergence, or game-rule bug requires evidence from devsam/core PHP or hwe/ts.
---

# OpenSamguk PHP Oracle

Read `AGENTS.md`, `CLAUDE.md`, and `docs/superpowers/WORKING_SYSTEM.md` completely.

For every behavior claim:

1. Locate `legacy/devsam-core` source and cite path plus line range. PHP wins every divergence.
2. Use `hwe/ts/` only as frontend grand truth where the PHP shell is silent; treat `legacy/devsam-core2026` as secondary structure guidance.
3. Map RNG draws, rounding, Korean logs, side-effect order, insertion order, and flush deltas.
4. Capture numeric or log goldens only through `tools/php-golden/`; never invent or weaken a fixture.
5. For live/UI bugs, combine `webapp-testing`, systematic debugging, and `loop-engineering`. If any required evidence is unavailable, report `채점대기` or blocked instead of shipping.
