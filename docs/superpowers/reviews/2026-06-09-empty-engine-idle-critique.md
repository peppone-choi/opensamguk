# Empty Engine Idle Critique

Date: 2026-06-09
Scope: game-engine boot and daemon lifecycle with admin-created servers

## Implementer Claim

Production now permits the intentional empty-server invariant: `world_state=0` is valid until an admin creates a server. The engine must start and stay alive without materializing the in-memory world or running ticks. Once `world_state` exists, the daemon may construct `TurnRunService` and resume normal one-daemon-write behavior.

## Critical Checks

- PHP parity logic is not changed: no RNG, rounding, Korean log, command, battle, or flush payload formulas are modified.
- No fake server/world data is introduced. Empty world remains empty; seeding stays controlled by `SCENARIO_SEED_ENABLED`.
- `InMemoryTurnWorld` construction is lazy, so a missing singleton row no longer fails Spring context refresh.
- `TurnDaemonRunner` checks `world_state` before resolving `TurnRunService`, preserving the existing service graph once a real world exists.
- Unit tests cover the empty-world idle path and the transition to a materialized service after world creation.

## Verdict

Verdict: cleared
