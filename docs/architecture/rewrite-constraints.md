# Rewrite Constraint Contract

This document defines a shared constraint contract for the rewrite so both the
turn daemon (in-memory) and API server (DB-backed) can evaluate constraints
consistently.

## Goals

- Single source of truth for constraint logic.
- Support full evaluation in daemon and precheck in API.
- Explicit data requirements for batching and caching.
- Deterministic reasons for deny vs unknown outcomes.

## Types (TypeScript sketch)

```ts
export type ConstraintResult =
  | { kind: "allow" }
  | { kind: "deny"; reason: string; code?: string }
  | { kind: "unknown"; missing: RequirementKey[] };

export type RequirementKey =
  | { kind: "general"; id: number }
  | { kind: "city"; id: number }
  | { kind: "nation"; id: number }
  | { kind: "destGeneral"; id: number }
  | { kind: "destCity"; id: number }
  | { kind: "destNation"; id: number }
  | { kind: "arg"; key: string }
  | { kind: "env"; key: string };

export interface ConstraintContext {
  actorId: number;
  cityId?: number;
  nationId?: number;
  destGeneralId?: number;
  destCityId?: number;
  destNationId?: number;
  args: Record<string, unknown>;
  env: Record<string, unknown>;
  mode: "full" | "precheck";
}

export interface StateView {
  has(req: RequirementKey): boolean;
  get(req: RequirementKey): unknown | null;
}

export interface Constraint {
  name: string;
  requires(ctx: ConstraintContext): RequirementKey[];
  test(ctx: ConstraintContext, view: StateView): ConstraintResult;
}
```

## Evaluation Flow

- `ConstraintPlanner` collects requirements across constraints.
- `StateView` loads those requirements (daemon: in-memory, API: DB).
- `test()` returns:
  - `allow` if constraint passes.
  - `deny` with a stable reason/code for UI.
  - `unknown` if required data is missing and `mode === 'precheck'`.

```ts
function evaluateConstraints(
  constraints: Constraint[],
  ctx: ConstraintContext,
  view: StateView,
): ConstraintResult {
  for (const constraint of constraints) {
    const missing = constraint.requires(ctx).filter((req) => !view.has(req));
    if (missing.length && ctx.mode === "precheck") {
      return { kind: "unknown", missing };
    }
    const result = constraint.test(ctx, view);
    if (result.kind !== "allow") {
      return result;
    }
  }
  return { kind: "allow" };
}
```

## StateView Selection Boundary

The split between in-memory and DB-backed evaluation happens outside the
constraint logic. A factory or loader chooses the `StateView` implementation
based on the execution environment:

- Turn daemon -> `InMemoryStateView` with a full in-memory snapshot.
- API server -> `DbStateView` (or `ProjectedStateView`) that fetches only the
  required fields from the DB or precomputed projections.

This keeps constraints pure and deterministic, while the infrastructure layer
decides how to satisfy `requires()` in each runtime.

## Mapping from Legacy Flags

- `REQ_GENERAL` -> `{ kind: 'general', id: actorId }`
- `REQ_CITY` -> `{ kind: 'city', id: cityId }`
- `REQ_NATION` -> `{ kind: 'nation', id: nationId }`
- `REQ_DEST_*` -> respective `dest` key
- `REQ_ARG` -> `{ kind: 'arg', key: <argName> }`
- `env` dependencies (for example `turnterm`, `year`) -> `{ kind: 'env', key: 'turnterm' }`

## Data Projection Suggestion

- API prechecks can rely on a small read model (for example `general_summary`)
  updated by the turn daemon; `StateView` selects the source per requirement.
