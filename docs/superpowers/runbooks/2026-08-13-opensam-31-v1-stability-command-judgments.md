# OPENSAM-31 v1 stability command judgments (D4-01~07)

This is the executable, source-cited companion to the active-plan
[D4-01~07 checklist](../plans/2026-07-13-v1-stabilization-and-v2-open-plan.md#v1-안정화-실행-체크리스트-d4-0107).
It documents commands and their judgment boundaries; it is **not** evidence that
any command, deployment, or production observation was executed while writing
this document.

## Common judgment rule

Run the Gradle rows from the repository root with JDK 21. A row is `PASS` only
when its command prints `BUILD SUCCESSFUL` **and** the targeted JUnit XML under
the named module's `build/test-results/test/` has `tests > 0`,
`failures="0"`, `errors="0"`, and `skipped="0"`. An absent XML file, zero
tests, or a skipped Testcontainers test is `HOLD`, not a pass. Any failed
assertion or missing `BUILD SUCCESSFUL` is `FAIL`.

The integration rows intentionally use Testcontainers. Docker unavailable is
therefore a `HOLD` for this operational check even though the test framework
classifies it as a skip. This follows the repository verification matrix's
executed-versus-skipped rule in
[docs/agent/verification.md](../../agent/verification.md#판정-규칙).

## D4-01 — seed

**Command**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.boot.ScenarioBootIT' --rerun-tasks
```

**PASS** — `ScenarioBootIT` records a fresh seed exactly once, finds 229
generals, 94 cities, and 2 nations, loads the same snapshot cohort, advances at
least one due turn, and confirms that the second seed is a no-op without
duplicating generals.

**FAIL / HOLD** — apply the common rule. In particular, a seed-count, snapshot,
turn-advance, or second-seed assertion failure is `FAIL`; no Docker-backed
execution is `HOLD`.

**Source** — the test's stated fresh-DB-to-tick contract and its concrete
assertions are in
[ScenarioBootIT](../../../app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScenarioBootIT.kt#L42-L53)
and
[its seed/load/tick/no-op assertions](../../../app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScenarioBootIT.kt#L100-L184).
The production seed runner delegates to `SeedBootstrap.ensureSeeded` at
[ScenarioSeedRunner.kt](../../../app/game-engine/src/main/kotlin/opensamguk/engine/boot/ScenarioSeedRunner.kt#L49-L59).

## D4-02 — load / restart

**Command**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test --tests 'opensamguk.engine.boot.WorldSnapshotLoaderDurableStateIT' --tests 'opensamguk.engine.boot.WorldSnapshotLoaderWorldScopeIT' --rerun-tasks
```

**PASS** — both targeted XML reports meet the common rule. The durable-state
test must restore persisted `plock`, PHP `starttime`, canonical `startTime`,
status, and cadence. The world-scope test must rebuild two worlds with identical
local entity IDs while retaining each world’s own state, nation, troop,
diplomacy, and access-log cohort.

**FAIL / HOLD** — apply the common rule. A restored-state mismatch or any
cross-world value is `FAIL`; an unavailable Docker-backed test is `HOLD`.

**Source** — durable restart-state assertions:
[WorldSnapshotLoaderDurableStateIT](../../../app/game-engine/src/test/kotlin/opensamguk/engine/boot/WorldSnapshotLoaderDurableStateIT.kt#L50-L85).
Identical-local-ID world isolation is asserted in
[WorldSnapshotLoaderWorldScopeIT](../../../app/game-engine/src/test/kotlin/opensamguk/engine/boot/WorldSnapshotLoaderWorldScopeIT.kt#L54-L88)
using the paired fixture at
[lines 96–205](../../../app/game-engine/src/test/kotlin/opensamguk/engine/boot/WorldSnapshotLoaderWorldScopeIT.kt#L96-L205).
`WorldSnapshotLoader.buildSnapshot()` calls the idempotent seed before reading
at [WorldSnapshotLoader.kt](../../../app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt#L49-L60).

## D4-03 — intake

**Command**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.web.CommandControllerIT' --rerun-tasks
```

**PASS** — both available and forecast-reservation paths return HTTP `202`, an
`AVAILABLE` status, a non-empty `requestId`, and exactly one command-stream
entry.

**FAIL / HOLD** — apply the common rule. Any missing `202`, status,
`requestId`, or expected stream-size assertion is `FAIL`; Docker-unavailable
skip is `HOLD`. `202` means intake acceptance only: it is not a daemon result,
and this test does not prove request-ID-to-stream-payload correlation or command
resolution.

**Source** — the controller IT scope and its two observed assertions are in
[CommandControllerIT](../../../app/game-api/src/test/kotlin/opensamguk/gameapi/web/CommandControllerIT.kt#L27-L37)
and
[lines 71–102](../../../app/game-api/src/test/kotlin/opensamguk/gameapi/web/CommandControllerIT.kt#L71-L102).
The test action (`che_농지개간`) uses the reserved-turn branch: it writes the
accepted inbox row, reserves the turn, records the accepted result, and then
publishes the wake at
[CommandReserveService.kt](../../../app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt#L146-L198).

## D4-04 — flush

**Command**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test --tests 'opensamguk.infra.persistence.JdbcFlushExecutorIT' --rerun-tasks
```

**PASS** — the actual PostgreSQL integration test meets the common rule and
confirms the ordered world-state → general → city → log-entry operations, then
reads back the expected post-state for general, city, world state, and the one
log entry.

**FAIL / HOLD** — apply the common rule. An ordered-operation or persisted-row
mismatch is `FAIL`; Docker-unavailable skip is `HOLD`. This row proves the JDBC
flush contract, not a separate claim that a particular live daemon run occurred.

**Source** — the test explicitly uses a JDBC transaction rather than JPA at
[JdbcFlushExecutorIT](../../../infra/src/test/kotlin/opensamguk/infra/persistence/JdbcFlushExecutorIT.kt#L23-L34),
and verifies the operation ordering and post-state at
[lines 197–310](../../../infra/src/test/kotlin/opensamguk/infra/persistence/JdbcFlushExecutorIT.kt#L197-L310).
The executor wraps `flush` in its transaction template at
[JdbcFlushExecutor.kt](../../../infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt#L46-L60).

## D4-05 — read

**Command**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.read.WorldScopedReadRepositoryIT' --rerun-tasks
```

**PASS** — the targeted XML meets the common rule and confirms that the process
world returns its own general, nation, city, general-turn, and nation-turn
cohorts while the other world’s rows are absent; its owner lookup also excludes
released rows.

**FAIL / HOLD** — apply the common rule. Own-world omission or cross-world
exposure is `FAIL`; Docker-unavailable skip is `HOLD`. This fixture uses
different IDs between its two worlds, so same-local-ID read isolation is not
claimed by this row.

**Source** — the explicit cohort and owner-lookup assertions are in
[WorldScopedReadRepositoryIT](../../../app/game-api/src/test/kotlin/opensamguk/gameapi/read/WorldScopedReadRepositoryIT.kt#L39-L76).
The production repositories receive `GameApiProcessWorld` and retain its scoped
`WorldId`, for example
[GeneralReadRepository](../../../app/game-api/src/main/kotlin/opensamguk/gameapi/read/GeneralReadRepository.kt#L341-L348).

## D4-06 — SSE relay ingress

**Command**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.sse.RealtimeRelayIT' --rerun-tasks
```

**PASS** — the targeted XML meets the common rule and the Redis listener
receives the published `turnCompleted` payload before its deadline, preserves
the exact payload, and decodes it as `RealtimeEvent.TurnCompleted`.

**FAIL / HOLD** — apply the common rule. A timeout, payload mismatch, or decode
failure is `FAIL`; Docker-unavailable skip is `HOLD`. This is a relay-ingress
check only: it invokes `fanOut`, but it does not attach an HTTP
`SseEmitter`/browser client, so browser-facing SSE delivery remains unproven by
this command.

**Source** — the ingress test and its exact assertions are in
[RealtimeRelayIT](../../../app/game-api/src/test/kotlin/opensamguk/gameapi/sse/RealtimeRelayIT.kt#L24-L29)
and
[lines 35–81](../../../app/game-api/src/test/kotlin/opensamguk/gameapi/sse/RealtimeRelayIT.kt#L35-L81).
The relay controller’s HTTP emitter and `turnCompleted` fan-out are implemented
at [RealtimeRelayController.kt](../../../app/game-api/src/main/kotlin/opensamguk/gameapi/sse/RealtimeRelayController.kt#L22-L59).

## D4-07 — deploy (local smoke boundary)

**Command**

```bash
./tools/smoke.sh
```

**PASS** — only for a deliberately started local Compose run: image build and
startup complete, each of gateway-api, game-api, game-engine, web-gateway,
web-game, and the nginx-to-gateway health boundary succeeds, and the script
prints `==> ALL SERVICES HEALTHY` before exiting zero.

**FAIL / HOLD** — a `FAIL: <service> not healthy` line, a missing required
health result, non-zero exit, or missing final success marker is `FAIL`.
Unavailable Docker or an intentionally unrun local stack is `HOLD`.

**Production boundary** — this command starts the **local** Compose stack and
tears it down only after every health check passes. On a failed health check,
its exit trap writes `tools/smoke.log` and the local stack can remain running;
local cleanup is therefore an operator action. It is not a production
deployment, does not authorize a main push, and cannot establish a production
Go decision. Production needs explicit human approval, a shared-control-plane
deployment, health and route checks, plus a real world-clock advance (or the
documented empty-world exception). Do not substitute the compatibility-only
`scripts/deploy.sh` for that approved process.

**Source** — local build/start, failure-log trap, health behavior, and
success-only teardown are in
[tools/smoke.sh](../../../tools/smoke.sh#L4-L37). The production approval,
shared-control-plane, route, and world-clock requirements are defined in
[docs/agent/lifecycle-ops.md](../../agent/lifecycle-ops.md#L11-L37)
and its completion criteria at
[lines 65–67](../../agent/lifecycle-ops.md#L65-L67).
