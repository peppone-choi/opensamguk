# OPENSAM-227 canonical V2 command schema review

Scope: `logic/src`, `common/src`, `app/game-api`, and `app/game-engine` canonical registry, intake, contextual precheck, typed wire mapping, daemon dispatch, and terminal result metadata for every current V2 command.
Verdict: cleared

The independent review covered `city.garrison.recruit` (`v2GarrisonRecruit`) and
`city.resources.transport` (`v2CityTransport`) and cleared the implementation after one
fix-required round.

## Findings resolved

1. The authenticated legacy V2 facades retain their frozen `IntakeAcceptedResponse` shape and alias
   code with an `AVAILABLE` acknowledgement, while the canonical endpoint returns a distinct,
   non-terminal `ACCEPTED` acknowledgement with a request ID.
2. Expiry is checked against an injected execution clock. The envelope's publication time remains
   available to handlers but cannot make a delayed command appear unexpired.
3. API contextual precheck and daemon execution call the same pure recruit/transport decisions,
   including stable deny codes and reasons.
4. Canonical, dedicated legacy, and generic legacy-alias mutation routes require an authenticated
   principal and verify the selected general belongs to that principal. Spring Security protects
   the same four route families before controller dispatch.
5. Canonical intake passes registry-validated typed arguments directly to typed wire construction.
   Integer overflow is blocked before wire construction; the canonical path does not parse raw JSON
   a second time.
6. Registry parsing rejects unexpected keys, quoted numerics, and malformed supplied optional
   numerics instead of silently substituting zero. Omitted optional amounts retain their documented
   zero default.
7. Result metadata names the emitted `CommandLifecycleResult`; the removed command-specific result
   classes were never emitted. Idempotency is truthfully `NOT_SUPPORTED`, and route revision is
   described as `PASSTHROUGH` rather than as an enforced optimistic-lock check.
8. The governing catalog now contains both registered command IDs and the same layer, ring,
   subject/target, authority policy/context, payload, adapter, and parity metadata as the registry.
9. A PostgreSQL/JPA integration fixture builds the API precheck state from real database rows and
   compares it with an equivalent daemon snapshot for both commands, including an allow and a
   stable denial code/reason for each. API state construction loads the actor and requested
   destination city instead of scanning every city in the world.
10. Transport wire reconstruction omits an absent `routeRevision` instead of converting omission
    into an explicit malformed null. Canonical, dedicated legacy, generic alias, and daemon tests
    cover the no-revision path.
11. Daemon direct dispatch samples its injected clock once, so execution and expiry observations
    cannot drift within one dispatch. A malformed envelope `sentAt` now emits a terminal
    `COMMAND_SENT_AT_INVALID` rejection for that request while later envelopes in the batch continue.
12. Transport execution converts a missing or invalid active-map selection into the shared
    `ROUTE_NOT_ADJACENT` terminal decision instead of throwing before the decision boundary.
13. Registry integer parsing uses exact numeric conversion for floating-point JSON numbers, so a
    positive-boundary exponent cannot saturate to `Long.MAX_VALUE` and pass validation.
14. The suggestion to replace recruit cost `phpRound` with Kotlin `round` was rejected: the
    OPENSAM-153 parity contract fixes this calculation to PHP half-away-from-zero behavior, making
    the compatibility helper intentional rather than incidental rounding code.
15. API contextual transport precheck now treats missing or invalid active-map configuration like
    daemon execution: state assembly leaves the map absent, non-throwing registry lookup yields a
    null hop distance, and the shared decision returns `ROUTE_NOT_ADJACENT`. Real PostgreSQL/JPA
    regressions cover both configuration failures instead of permitting an HTTP 500 path.
16. Transport precheck loads the requested source city by ID alongside the actor and destination,
    preserving bounded reads while preventing a valid non-actor source from becoming the false
    `FROM_CITY_NOT_FOUND` denial. A real PostgreSQL/JPA-to-daemon matrix covers every shared denial
    branch: eight recruit and sixteen transport cases, with exact code and reason parity.

## Coverage

| Catalog entry | Registry | Legacy facade | Typed wire | Terminal result |
|---|---|---|---|---|
| `city.garrison.recruit` | `garrisonRecruitSchema` | `v2GarrisonRecruit` | `CityGarrisonRecruit` | `CommandLifecycleResult` |
| `city.resources.transport` | `cityTransportSchema` | `v2CityTransport` | `CityTransport` | `CommandLifecycleResult` |

The scorecard is complete at 2/2 across the authoritative catalog, registry, facades, sealed wire
inventory, and emitted result contract.

- The registry contract covers canonical ID and optional aliases; catalog layer, `NONE` source ring,
  subject and target; actor, authority, policy ID and context version; payload version, adapter and
  parity status; actual argument/result types; all four availability states; truthful idempotency,
  expiry, replay event, and route-revision policy.
- The architecture gate reflects over the sealed V2 wire inventory and requires exact equality with
  both registry aliases and immediate-intake registrations.
- Unknown canonical IDs return `UNKNOWN_COMMAND` in the canonical and generic intake surfaces and
  never enter the legacy command registry or rest fallback.
- The final focused integration invocation was:
  `./gradlew --no-daemon --max-workers=1 --console=plain --rerun-tasks`
  `:app:game-engine:test --tests 'opensamguk.engine.e2e.VerticalSliceE2EIT'`
  `:app:game-engine:test --tests 'opensamguk.engine.v2.V2GarrisonRecruitDispatchTest'`
  `:app:game-api:test --tests 'opensamguk.gameapi.v2.V2CanonicalCommandControllerTest'`
  `--tests 'opensamguk.gameapi.web.CommandControllerSecurityTest'`.
  Its raw output and binary observables are retained outside the project worktree in the OPENSAM-227
  metarepo task report and evidence index.

## Independent reviewer

The first read-only review returned `FIX REQUIRED`, then `PASS` after its findings were fixed.
Later exact-goal, security, and context audits found the legacy-response, authentication,
metadata-truthfulness, malformed-argument, catalog, cross-adapter-fixture, and omitted-route-revision
gaps described above. Each finding is now represented by executable regression coverage; the final
immutable commit and CI run links are recorded in the task report because a commit cannot contain
its own SHA or a CI run created only after that commit is pushed.

## Full-suite follow-up

The first healthy serialized full backend run exposed five `V2CityTransportRulesTest` fixture
failures: the handler correctly required the active world map, but the unit fixture constructed a
world without `config.mapName`. The fixture now declares the production-valid `che` map. The final
full backend invocation was
`./gradlew --no-daemon --max-workers=1 --console=plain --rerun-tasks :common:test :logic:test`
`:infra:test :app:game-engine:test :app:game-api:test`. The external OPENSAM-227 metarepo task report
and evidence index retain the raw output, module-level JUnit totals, skip identity, immutable commit,
and hosted CI run URL; this committed review intentionally does not claim inaccessible worktree-local
artifacts or embed a self-referential commit SHA.
