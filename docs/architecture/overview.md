# Architecture Overview

This repository contains both the active legacy PHP game and an in-progress
TypeScript rewrite. The legacy engine remains the source of truth while the
monorepo plan is prepared alongside it.

## Project Naming

- Official name: 삼국지 모의전투 HiDCHe
- Common nicknames: 삼모, 삼모전, 힏체섭
- Short forms in code/docs: sammo, hidche
- TypeScript rewrite working name: sammo-ts

## Layers

- Legacy runtime: PHP entry points under `legacy/` and `legacy/hwe/`
- Legacy engine core: domain logic under `legacy/hwe/sammo/`
- Legacy frontend: Vue/TypeScript under `legacy/hwe/ts/`
- Rewrite (in progress): pnpm workspace monorepo under `packages/` and `app/`

## Current Implementation Notes

- `packages/infra` is live with Prisma/Postgres connectors used by game services.
- `app/game-engine` implements an in-memory turn daemon with DB flush hooks and Redis control transport.
- `app/game-api` exposes tRPC endpoints for reserved turns, messages, and battle sims, communicating with the daemon via Redis.
- Gateway/game frontends are still placeholders (not part of current runtime).

## Legacy Data Migration Policy

- Data under `legacy/` is migration-only and not used by the rewrite at runtime.
- After DB migration completes, legacy data is no longer required.

## Data and State

- PHP engine owns authoritative gameplay state today
- Scenario and unit pack data are loaded from `legacy/hwe/scenario/`
- Deterministic RNG is required for gameplay outcomes
- Build/runtime profiles are server+scenario pairs; scenario selection is required
  because it drives unit sets and DB settings.

## Legacy Docs

- Legacy entities and DB schema overview: `docs/architecture/legacy-entities.md`
- Legacy engine map: `docs/architecture/legacy-engine.md`
- Postgres schema proposal (rewrite): `docs/architecture/postgres-schema.md`

## Cross-Cutting Policies

- No ad-hoc randomness for gameplay; use deterministic RNG
- External JSON/data inputs must be validated with zod; name zod schemas with a `z` prefix.
- Keep domain logic independent of endpoints or UI
- Prefer clear Korean comments in core gameplay logic for maintainers
- Test strategy and layering: `docs/testing-policy.md`

## Runtime Processing (Outline)

This document links to detailed runtime behavior in `docs/architecture/runtime.md`.
Use that document for turn daemon scheduling, API request handling, and
persistence sequencing.

The turn daemon lifecycle and control contract are documented in
`docs/architecture/turn-daemon-lifecycle.md`.

## Documentation TODOs

- Pending follow-ups: `docs/architecture/todo.md`.
