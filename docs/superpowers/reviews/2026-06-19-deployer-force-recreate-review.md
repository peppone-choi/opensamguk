# Deployer Force-Recreate Review — 2026-06-19

## Scope

- Task: make an admin server promotion actually replace the stateless game containers that serve `/game/s1`.
- Changed files:
  - `.github/workflows/deploy.yml`
  - `docs/loops/page-parity/LEDGER.md`
  - `docs/superpowers/reviews/2026-06-19-deployer-force-recreate-review.md`

## Baseline Evidence

- `s1` deploy status showed `currentTag=311890421e761de2ee6246e20176fa5ee39621f7`.
- Live `/game/s1` still rendered `button.city-base=94` and `a.city-base=0`.
- Live `/game/s1` loaded old page chunk `page-4b0b67ec68b163f1.js`.
- Local source and local production build contain the anchor implementation and a newer page chunk (`page-79f669ede7d7c0e7.js`).
- Re-running the same-tag admin deploy returned:
  - `web-game Pulled`
  - `game-api Pulled`
  - `Container s1-game-api Running`
  - `Container s1-web-game Running`

## Root Cause

The deployer changes `servers/<id>.env` and runs `docker compose up -d --no-deps game-api web-game`. In the observed live case, Compose did not recreate the existing stateless containers; it left them `Running`. That lets the env/status tag say "latest" while the old `web-game` process continues serving the old Next route shell and client chunk.

There is a second live topology hazard: if the shared compose stack has a `game-frontend` service, nginx can serve `/game/_next/*` assets from that shared frontend. The main deploy workflow must keep that shared asset frontend current when it exists, or a newly recreated server frontend can reference chunks that the shared asset frontend does not have.

## Change

- The main deploy job patches the live EC2 stack deployer source to require `--force-recreate`, rebuilds/restarts the deployer, then verifies deployer compatibility.
- The main deploy job force-recreates shared stateless upstreams after pull.
- The main deploy job includes `game-frontend` in shared pull/force-recreate only when the live shared compose defines that service.

## Why This Scope

- `game-engine` remains excluded from automatic stateless bounce, preserving the running-world desync rule.
- Existing server version pins are still preserved. A server changes only when an operator/admin promotes its `IMAGE_TAG`.
- The shared `game-frontend` update is conditional, so installs without that legacy service keep the documented shared-only path.

## Local Verification

- `ruby -e "require 'yaml'; YAML.load_file('.github/workflows/deploy.yml'); puts 'yaml ok'"`: passed.
- `git diff --check`: passed.
- `go test ./...` in `legacy/opensamguk-docker/deployer`: not run locally; Go toolchain is not installed in this Codex environment.
- `tools/agent-system/check.py --strict --base origin/main --format json`: pending fresh-review verdict.

## Production Acceptance

After merge and deploy:

- Main deploy run succeeds and rebuilds/restarts `opensamguk-deployer`.
- Re-running admin deploy for `s1` returns compose detail with `s1-web-game` recreated instead of only `Running`.
- `/game/s1` DOM renders `a.city-base=94` and `button.city-base=0`.
- Clicking city 1 commits to `/game/s1/city?id=1`.

## Fresh Review

Verdict: cleared

Fresh correctness reviewer Boole the 2nd initially returned `fix-required`: shared `game-frontend` could still remain `Running` without a forced recreate. The workflow now uses `docker compose up -d --force-recreate --no-deps $SHARED_SERVICES`, so the shared asset frontend is also replaced when present.

Checked invariants from the review:

- The deployer stateless path remains bounded to `game-api` and `web-game`; `game-engine` is not included.
- The game server version-pin preservation step still only reads and reports `servers/s*.env` pins.
