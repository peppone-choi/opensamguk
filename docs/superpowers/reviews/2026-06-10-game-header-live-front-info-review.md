# Game Header Live Front Info Review

## Scope

- `web/game/components/Header.tsx` removes the hardcoded `184년 1월` display.
- The shared game header now reads the selected server through the existing `api.frontInfo()` path.

## Source Of Truth

- `front-info.global.year` and `front-info.global.month` are the live server clock.
- `sam_server` routing is already handled by the `/api/game` proxy, so this header uses the same server selection path as the rest of the game UI.

## Parity Evidence

- The change does not alter game logic, command execution, RNG, logs, or persistence.
- It removes a fabricated display value and replaces it with the same live front-info data already used by `GameChrome`.

## Critical Review

Verdict: cleared

- Risk: front-info can fail before the page is usable. Mitigation: the header shows a neutral failure state instead of a fabricated year.
- Risk: duplicate front-info calls can occur because `GameChrome` also loads front-info. The call is read-only and keeps the shared header correct even on no-general/claim screens.

## Verification

- `/usr/local/bin/pnpm --dir web/game typecheck`
- `python3 tools/agent-system/check.py --strict --base origin/main`
- `git diff --check`

