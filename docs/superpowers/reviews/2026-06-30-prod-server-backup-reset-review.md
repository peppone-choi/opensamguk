# Production Server Backup Reset Review

## Scope

Added a manual GitHub Actions workflow for destructive game-server resets:

- `.github/workflows/reset-game-server.yml`

This is an operations surface, not a PHP behavior port. PHP oracle/golden capture is not applicable because the workflow only backs up and calls the existing deployer reset contract.

## Baseline

Live `s2` after the scenario map seed fix:

- `/api/game/api/map/preview`: `mapCode=miniche_b`, 78 cities, first city `낙양`.
- `/api/game/api/const`: `mapName=miniche_b`, 78 city constants, first city `낙양`.
- `/api/game/api/cities`: 94 cities, first city `업`.
- `/api/game/api/city/1`: `업`.

Conclusion: code and static catalog are fixed, but the already-seeded `s2` database still contains stale `che` city rows.

## Change

The new workflow is `workflow_dispatch` only and requires `confirm=RESET <server>`.

Before calling the deployer reset endpoint, it:

- copies the server env file into `$HOME/opensamguk-backups/<server>/<timestamp>/`;
- writes a gzipped `pg_dump` of the game database when the postgres container exists;
- stores row-count evidence for `world_state`, `city`, `nation`, and `general`;
- saves the Redis data directory best-effort.

Then it calls the existing internal deployer `/servers/reset` endpoint from the Docker network, waits for `game-api`, `game-engine`, and `/game/<server>`, and asserts the public game API city catalog when expected values are provided.

## Risk Review

- Destructive reset risk: mitigated by mandatory confirmation plus pre-reset backup.
- Wrong scenario risk: workflow inputs expose `scenario_code`; the default is `scenario_2` for the current `s2` repair.
- Silent partial reset risk: workflow waits on both backend containers and the public route, then verifies `/api/game/api/cities` and `/api/game/api/city/1`.
- Push deploy risk: normal source push still preserves game-server pins; this workflow is manual and independent of automatic shared-stack deploy.

## Verification

- YAML parse: `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/reset-game-server.yml")'`
- Shell parse: extracted `run:` body and checked with `bash -n`.

## Known Gaps

- The workflow is not a replacement for point-in-time database backups. It is a pre-reset safety net for operator-triggered server rebuilds.
- Redis backup is best-effort because Redis state is reset-oriented queue/cache state in this stack.
