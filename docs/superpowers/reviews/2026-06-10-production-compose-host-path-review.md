# Production Compose Host Path Review - 2026-06-10

## Scope

- Production lifecycle symptom: deployer-triggered shared reload can recreate nginx from inside `/workspace`, causing Docker to resolve the nginx bind mount as a non-host path.
- Changed file: `docker-compose.production.yml`.

## Source Of Truth

- Docker bind mounts are resolved by the host Docker daemon.
- The deployer runs compose inside its container, where the repository is mounted at `/workspace`.
- Host-run production compose still needs to work from the checkout root.

## Review Result

Verdict: cleared

- The nginx config bind source now resolves through `COMPOSE_HOST_DIR` when explicitly provided.
- If `COMPOSE_HOST_DIR` is unset, compose falls back to `${PWD}` and then `.` for normal host-run usage.
- No gameplay logic or runtime routing behavior changes.

## Verification

- `COMPOSE_HOST_DIR=/host/opensam docker compose --env-file .env.example -f docker-compose.production.yml config` resolves nginx config to `/host/opensam/infra/nginx/nginx.conf`.
- `docker compose --env-file .env.example -f docker-compose.production.yml config` resolves nginx config to the local checkout path.
- `git diff --check`.

## Documentation Decision

No README update is needed. This is a production compose path correction for the existing nginx config mount.
