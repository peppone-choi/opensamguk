# TypeScript Rewrite Plan

The rewrite targets a pnpm workspace-based monorepo with Node.js services and
Vue 3 frontends.

## Planned Layout

- `/packages/common`: shared utilities and type definitions (RNG/bytes/테스트 RNG 포함, no infra)
- `/packages/infra`: Prisma/Redis connectors and other runtime infra
- `/packages/logic`: pure game logic with DI and interfaces
- `/app/gateway-frontend`: gateway UI
- `/app/gateway-api`: gateway service
- `/app/game-frontend`: game UI
- `/app/game-api`: game backend per server+scenario profile
- `/app/game-engine`: turn daemon per server+scenario profile
- `/tools/build-scripts`: build and deployment scripts

## Current Implementation Notes

- `packages/infra` is live and used by game-api/game-engine services.
- `app/game-api` and `app/game-engine` have initial implementations (tRPC endpoints, turn daemon loop).
- Redis transport for turn daemon control is implemented and wired.
- Frontend apps remain placeholders while backend/runtime stabilizes.

## Runtime Stack (Planned)

- Backend: Node.js + Fastify
- API: tRPC + zod
- ORM: Prisma
- Frontend: Vue 3, Pinia, Vue Router, TailwindCSS, Vite
- Data: PostgreSQL, Redis sessions
- Testing: Vitest

## Frontend Direction

- Gateway and game apps are separate SPAs (`/app/gateway-frontend`, `/app/game-frontend`).
- UI visuals should stay close to the legacy look, but layout changes are allowed
  as long as required information is preserved.
- Prefer client-driven rendering: fetch most data via API and let the client own
  data shaping and presentation unless the data must be hidden.

## Constraint Evaluation Contract

The shared constraint contract (daemon vs API precheck split) is documented in
`docs/architecture/rewrite-constraints.md`.

## Legacy Data Migration

- Legacy data is for migration only; the rewrite runtime does not depend on it.
- Once DB migration is complete, legacy data can be retired.

## Profiles (Planned)

- Profiles are server+scenario pairs; scenario selection is required for build/runtime.
- Server IDs: `che`, `kwe`, `pwe`, `twe`, `nya`, `pya`
