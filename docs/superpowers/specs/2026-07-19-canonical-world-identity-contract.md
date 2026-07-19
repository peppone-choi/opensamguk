# Canonical world identity contract

> Status: approved foundation contract
> Date: 2026-07-19
> Tracking: Jira `OPENSAM-148` / GitHub `#298`
> Scope: build-only canonical identity foundation for CQRS hardening

## 1. Canonical identity

The canonical identity of a game world is PostgreSQL `world_state.id`. Its storage
type is SQL `INTEGER`, and its Kotlin representation is `WorldId`, a
`@Serializable @JvmInline` wrapper around a positive `Int`.

`WorldId` construction rejects zero and negative values. It has no default,
singleton, profile-derived, server-derived, or fallback factory. A caller must
provide an explicit positive value obtained from the canonical world row.

On the wire, the field is named `worldId` and is an integer JSON scalar:

```json
{"worldId":42}
```

`"42"`, `{ "value": 42 }`, or an omitted field are not alternate encodings of
the identity.

## 2. Values that are not world identity

`profile`, `server_id`, and `ng_games.id` are operational or legacy identifiers;
none is an alias for `world_state.id`. They must not be converted into a
`WorldId`, used as a default for one, or substituted into SQL, Redis, wire, or
authorization scope.

## 3. Required boundary behavior

Every world-owned request, command, read, Redis key, and persisted row must have
an explicit canonical world id. Absence is rejected; the system must not infer a
"default world." If the request/context identity disagrees with the selected
`world_state.id`, a row's `world_id`, or a key's world component, the operation
rejects before it can read or mutate data.

This rejection rule applies equally to an invalid scalar, an omitted `worldId`,
and a mismatch between independently supplied world identifiers. A valid local
entity or request id never repairs a missing or mismatched world id.

## 4. Composite ownership keys

World-owned records are addressed by a composite key, never a globally assumed
local id:

- entity records: `(world_id, local_id)`;
- command/result/inbox/outbox records: `(world_id, request_id)`.

Future scoped foreign keys, unique constraints, SQL predicates, cache keys, and
Redis consumer identities use the same world component. This contract does not
authorize an unscoped compatibility lookup at runtime.

## 5. Single-world expand/backfill rule

A later expand migration may backfill a new `world_id` column only after it has
verified that the database has **exactly one** `world_state` row. The backfilled
value is that row's `id`. Zero rows, more than one row, an orphaned row, or an
unresolvable source identity is a fail-closed migration error; it is never a
reason to choose a profile, `server_id`, or `ng_games.id` as a substitute.

This is a temporary single-world expand rule, not a second-world admission
mechanism and not a runtime defaulting rule.

## 6. Sequencing and scope fence

`OPENSAM-148` establishes this contract and blocks both broad V2 `OPENSAM-43`
and scoped-schema `OPENSAM-126`. `OPENSAM-43` retains its full broad V2 scope
and remains open; this foundation neither narrows that ticket nor marks it done.

The contract and the `WorldId` type are build-only work. Local/live
`OPENSAM-123` proof and `OPENSAM-124` W3 durable binding are activation/cutover
gates, not blockers to approving this contract or building the identity → S2 →
S3 → S4 foundation sequence.

The following are explicitly out of scope here:

- second-world admission and its point-of-no-return cutover;
- full table migration, constraint rollout, or backfill implementation;
- W3 durable inbox/outbox activation or any production activation;
- schema, daemon write-path, API, Redis, or deployment changes.
