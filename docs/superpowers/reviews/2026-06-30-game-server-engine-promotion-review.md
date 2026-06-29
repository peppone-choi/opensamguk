# Game Server Engine Promotion Review

Scope: add a manual production workflow for promoting existing game server stacks when a release must also restart `game-engine`.

Implementer claim:
- The existing push deploy preserves server pins and the admin deployer intentionally bounces only `game-api` and `web-game`.
- The 36-turn calendar/KST release changes shared logic used by the turn daemon, so an engine-inclusive promotion path is required for a complete production rollout.
- The new workflow runs only on the `ec2-prod` self-hosted runner and requires an immutable tag input.

Critique:
- Risk: automatic push deploy must not start or reset game servers. The workflow is `workflow_dispatch` only, so normal pushes still preserve pins.
- Risk: untrusted tags or server ids could be injected into shell commands. The workflow rejects tags outside `[A-Za-z0-9._-]` and server ids outside `s[A-Za-z0-9_-]+`.
- Risk: engine restart could lose state. The deployment environment documents that `game-engine` rehydrates `InMemoryTurnWorld` from `world_state`; the workflow restarts via the existing server compose file rather than deleting volumes.
- Risk: accidental DB reset. The workflow does not call `/servers/reset`, `down --volumes`, or any destructive compose command.

Verification plan:
- Run `tools/agent-system/check.py --format json`.
- Run `git diff --check`.
- Push and wait for CI/deploy success so the requested tag exists in GHCR.
- Dispatch this workflow with `include_engine=true`, then verify deploy status and actuator health for `s1`/`s2`.

Follow-up critique after first dispatch:
- The first run recreated and started `s1` containers, but failed because actuator verification ran immediately after `docker compose up -d`.
- The workflow now retries `game-api`, `game-engine`, and the public `/game/<server>` route before failing, preserving the same non-destructive deployment behavior.

Verdict: cleared
