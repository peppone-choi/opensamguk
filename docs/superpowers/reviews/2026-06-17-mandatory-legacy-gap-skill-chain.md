# Mandatory legacy-gap skill chain review

## Scope

- Files: `docs/superpowers/LOOP_ENGINEERING.md`, `AGENTS.md`, `CLAUDE.md`.
- Change type: load-bearing workflow rule, explicitly requested by the user on 2026-06-17.

## Required chain

Legacy gap, UI parity, and production bug loops must use:

1. `opensamguk-php-oracle` for PHP grand-truth or `hwe/ts/` path + line evidence.
2. `webapp-testing` for UI reproduction and browser/API observation.
3. `systematic-debugging` for root-cause convergence before implementation.
4. `loop-engineering` for baseline, one hypothesis, grader, and adopt/revert evidence.

## Review

- This does not weaken parity gates or golden expectations.
- The shared source of truth remains `docs/superpowers/LOOP_ENGINEERING.md`; Claude and Codex adapters stay thin.
- `AGENTS.md` and `CLAUDE.md` now point contributors to the same chain so provider-specific surfaces do not diverge.
- Failure to run one link must be recorded as `채점대기` or `blocked`; silent ship/merge is disallowed.

## Verdict

`pass` for documentation consistency. Implementation work still needs the normal repo gates for each bug loop.
