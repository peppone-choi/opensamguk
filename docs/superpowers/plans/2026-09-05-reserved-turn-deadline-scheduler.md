# Reserved Turn Deadline Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute reserved general turns within the daemon's 250 ms observation bound without advancing the five-minute world clock, and remove the client's mandatory 300 ms first-result delay.

**Architecture:** The daemon arbitrates two deadlines: an overdue world boundary always runs the existing full tick, while a due general deadline before the next world boundary runs a new non-clock-advancing general drain. Both paths retain the existing deterministic lifecycle, writer fence, generation flush, command-result publication, pause gate, and recovery gate.

**Tech Stack:** Kotlin 2.1, Spring Boot, JUnit/Kotlin Test, Gradle 8.12, TypeScript, Next.js 15, Vitest 3, PostgreSQL/JDBC, Redis Streams

**Spec:** `docs/superpowers/specs/2026-09-05-reserved-turn-deadline-scheduler-design.md`

## Global Constraints

- `world_state.last_turn_time`, year, month, and phase advance only in `TurnRunService.runTick`.
- General due selection remains strict `turnTime < executionAsOf` and ordered by `(turnTime, generalId)`.
- All engine writes flow through `ChangeRecorder` and `JdbcFlushExecutor`; no JPA or inline daemon write is added.
- An overdue world boundary takes priority over a general-only deadline.
- The default daemon observation bound is exactly `250` milliseconds; global `tickSeconds` remains unchanged.
- A general-only retained flush retry must not publish `turnCompleted`.
- Project changes stay in `work/opensamguk/reserved-turn-deadline-scheduler` and end as one logical implementation commit with the required co-author trailer.

---

### Task 1: Expose the earliest strict general deadline

**Files:**
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnDaemonLifecycle.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/turn/DrainTailAdvanceTest.kt`

**Interfaces:**
- Produces: `fun nextGeneralRunTime(): Instant?`
- Contract: returns `min(general.turnTime) + 1ns`, or `null` when no general exists.

- [x] **Step 1: Write the failing deadline tests**

Add tests using literal instants. The production mutation each test catches is returning the raw equal-time boundary, choosing a non-minimum general, or inventing a deadline for an empty world.

```kotlin
@Test
fun `next general run time is one nanosecond after the earliest strict turn time`() {
    val early = Instant.parse("0200-01-01T00:00:05Z")
    val late = Instant.parse("0200-01-01T00:00:09Z")
    val lifecycle = lifecycleWithGenerals(late, early)

    assertEquals(early.plusNanos(1), lifecycle.nextGeneralRunTime())
}

@Test
fun `next general run time is absent when the world has no generals`() {
    assertNull(lifecycleWithGenerals().nextGeneralRunTime())
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests opensamguk.engine.turn.DrainTailAdvanceTest --rerun-tasks
```

Expected: compilation fails because `nextGeneralRunTime` does not exist.

- [x] **Step 3: Implement the minimal lifecycle query**

```kotlin
fun nextGeneralRunTime(): Instant? =
    world.listGenerals().minOfOrNull { it.turnTime }?.plusNanos(1)
```

- [x] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command. Expected: `DrainTailAdvanceTest` passes with zero failures.

---

### Task 2: Add a non-clock-advancing general drain

**Files:**
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnRunServiceIT.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnRunServiceFlushRecoveryTest.kt`

**Interfaces:**
- Consumes: `TurnDaemonLifecycle.nextGeneralRunTime()` and existing `lifecycle.runTick(executionAsOf, cohort)`.
- Produces: `open fun nextGeneralRunTime(): Instant?` and `open fun runDueGeneralTurns(executionAsOf: Instant): TickResult`.
- Contract: flushes due general deltas/results once while preserving the current world clock and calendar.

- [x] **Step 1: Write a failing service integration test for the preserved world clock**

Seed a world whose `last_turn_time` is `0200-01-01T00:00:00Z` and a general whose `turn_time` is five seconds later. Reserve a command, call `runDueGeneralTurns(0200-01-01T00:00:05.000000001Z)`, and assert literals:

```kotlin
assertEquals("0200-01-01T00:00:00Z", world.getState().lastTurnTime.toString())
assertEquals("0200-01-01 00:00:05", handled.single().date)
assertEquals(Instant.parse("0200-01-01T00:05:05Z"), world.getGeneral(generalId)!!.turnTime)
```

Also inspect the emitted flush payload and assert its `last_turn_time`, year, month, and phase are unchanged after the flush. The existing Docker-backed `TurnRunServiceIT` remains in the focused regression gate.

- [x] **Step 2: Write a failing recovery test**

Use the existing retaining flush executor fixture to fail the general-only flush once, retry it, and assert that the in-memory and retained payload clock values remain the pre-drain literals and that no `turnCompleted` publication occurs.

- [x] **Step 3: Run the focused recovery test and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests opensamguk.engine.run.TurnRunServiceIT \
  --tests opensamguk.engine.run.TurnRunServiceFlushRecoveryTest --rerun-tasks
```

Expected: compilation fails because `runDueGeneralTurns` and `nextGeneralRunTime` do not exist.

- [x] **Step 4: Implement the service entry points and shared non-clock flush**

Add the forwarding query:

```kotlin
open fun nextGeneralRunTime(): Instant? = lifecycle.nextGeneralRunTime()
```

Implement `runDueGeneralTurns` with this exact sequence:

```kotlin
recoveryGate.requireIntakeOrTickAllowed("general turn")
commandOutboxRelay?.publishPending()
val claimed = claimExecutableEnvelopes(commandBlockMs)
val intakeResults = commandDispatcher?.dispatchEnvelopes(claimed.map { it.envelope }).orEmpty()
val cohort = lifecycle.snapshotGeneralDrainCohort()
val handled = lifecycle.runTick(executionAsOf, cohort)
val state = world.getState()
val base = buildFlushPayload()
val worldState = currentWorldStateUpdate(base.worldStateUpdate, state)
val committedWorldVersion = state.worldVersion + 1
val commandResults =
    intakeResults.toCommandResultRows(committedWorldVersion) +
        handled.toExecutionCommandResultRows(committedWorldVersion)
val payload = base.copy(worldStateUpdate = worldState, commandResults = commandResults)
flushWithGeneration(payload)
acknowledgeClaimedWakes(claimed)
publishCommandResults(commandResults)
return TickResult(
    handled = handled,
    flushedGenerals = payload.updatedGenerals.size,
    flushedCities = payload.updatedCities.size,
    flushedLogs = payload.logEntries.size,
    turnCompletedAt = executionAsOf.toString(),
    lastTurnTime = state.lastTurnTime.toString(),
)
```

Extract `currentWorldStateUpdate` from the already-working `runIntakeCommands` state-map construction. It must copy the current year/month/phase/last-turn fields and apply the existing writer fence. Do not call `applyCommittedWorldClockFromPayload` on the new path.

At the start of `applyCommittedWorldClockFromPayload`, return after parsing the payload time when it equals `previousTurnTime`. This makes retained intake and general-only payload retries non-clock-advancing while leaving full tick retries unchanged.

- [x] **Step 5: Run the focused tests and verify GREEN**

Run the Step 3 command. Expected: both classes pass; Docker-gated cases may report skipped only under the repository's documented Testcontainers condition.

---

### Task 3: Arbitrate world and general deadlines in the runner

**Files:**
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonRunner.kt`
- Test: `app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnDaemonRunnerTest.kt`

**Interfaces:**
- Consumes: `TurnRunService.nextRunTime()`, `nextGeneralRunTime()`, `runTick()`, and `runDueGeneralTurns()`.
- Produces: world-first catch-up arbitration and a default `idlePollMs` of `250`.

- [x] **Step 1: Extend the runner stub and write failing behavior tests**

Add separate counters/latches for full ticks and general drains. The production mutations caught are routing a personal deadline through `runTick` or allowing a personal drain to leapfrog an overdue world boundary.

```kotlin
@Test
fun `runner drains a due general before a future world boundary`() {
    val generalLatch = CountDownLatch(1)
    val service = StubService(
        ticks = AtomicInteger(),
        initialNextRun = Instant.now().plusSeconds(60),
        initialNextGeneralRun = Instant.now().minusMillis(1),
        generalDrainLatch = generalLatch,
    )
    val runner = TurnDaemonRunner(provider(service), WORLD_EXISTS, DaemonPauseGate(), true, 250)
    runner.start()
    try {
        assertTrue(generalLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, service.generalDrains.get())
        assertEquals(0, service.ticks.get())
    } finally {
        runner.stop()
    }
}

@Test
fun `runner prioritizes an overdue world boundary over a due general`() {
    val order = CopyOnWriteArrayList<String>()
    val worldLatch = CountDownLatch(1)
    val service = StubService(
        ticks = AtomicInteger(),
        initialNextRun = Instant.now().minusSeconds(1),
        initialNextGeneralRun = Instant.now().minusSeconds(2),
        callOrder = order,
        latch = worldLatch,
    )
    val runner = TurnDaemonRunner(provider(service), WORLD_EXISTS, DaemonPauseGate(), true, 250)
    runner.start()
    try {
        assertTrue(worldLatch.await(3, TimeUnit.SECONDS))
        assertEquals("world", order.first())
    } finally {
        runner.stop()
    }
}
```

The stub exposes the latches/counters used above and overrides:

```kotlin
override fun nextGeneralRunTime(): Instant? = nextGeneral
override fun runDueGeneralTurns(executionAsOf: Instant): TickResult {
    callOrder?.add("general")
    generalDrains.incrementAndGet()
    nextGeneral = executionAsOf.plusSeconds(60)
    generalDrainLatch?.countDown()
    return emptyTickResult(executionAsOf)
}
```

- [x] **Step 2: Run `TurnDaemonRunnerTest` and verify RED**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests opensamguk.engine.run.TurnDaemonRunnerTest --rerun-tasks
```

Expected: compilation or assertions fail because the runner has no personal-deadline branch.

- [x] **Step 3: Implement world-first deadline arbitration**

After the pause/recovery gates:

```kotlin
val now = Instant.now()
val nextWorldRun = activeService.nextRunTime()
if (!now.isBefore(nextWorldRun)) {
    runWorldTick(activeService, nextWorldRun)
    continue
}

val nextGeneralRun = activeService.nextGeneralRunTime()
if (nextGeneralRun != null && !now.isBefore(nextGeneralRun)) {
    activeService.runDueGeneralTurns(now)
    continue
}

if (activeService.runIntakeCommands(blockMs = 1) > 0) continue
val nextDeadline = listOfNotNull(nextWorldRun, nextGeneralRun).minOrNull()!!
val waitMs = minOf(Duration.between(Instant.now(), nextDeadline).toMillis(), idlePollMs).coerceAtLeast(1)
Thread.sleep(waitMs)
```

Keep existing tick diagnostics around the full world tick. General drain failures stay inside the existing outer exception/recovery path. Change the constructor default expression to `opensamguk.daemon.idle-poll-ms:250` and update comments/log wording.

- [x] **Step 4: Run runner and recovery regressions and verify GREEN**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests opensamguk.engine.run.TurnDaemonRunnerTest \
  --tests opensamguk.engine.run.TurnRunServiceFlushRecoveryTest --rerun-tasks
```

Expected: both classes pass with zero failures.

---

### Task 4: Remove the first 300 ms client wait

**Files:**
- Modify: `web/game/lib/api.ts`
- Test: `web/game/__tests__/commandSubmit.test.ts`
- Test: `web/game/__tests__/commandSubmit.result-events.test.ts`

**Interfaces:**
- Produces: `pollCommandResultResponse` performs attempt zero immediately and spaces only retries by 300 ms.

- [x] **Step 1: Write a failing fake-timer test**

Mock `api.commandResult` with a complete resolved reservation result and assert it is called after only a microtask turn, before advancing timers:

```typescript
const pending = pollCommandResultResponse('req-immediate');
await Promise.resolve();
expect(api.commandResult).toHaveBeenCalledWith('req-immediate');
await expect(pending).resolves.toMatchObject({ status: 'RESOLVED', type: 'reservationAccepted' });
```

Add a second test where the first response is `PENDING`; assert the second lookup does not happen until fake time advances by 300 ms.

- [x] **Step 2: Run the focused Vitest files and verify RED**

```bash
cd web
pnpm --filter @opensamguk/web-game exec vitest run \
  __tests__/commandSubmit.test.ts \
  __tests__/commandSubmit.result-events.test.ts \
  --maxWorkers=1
```

Expected: the immediate-call assertion fails because the implementation sleeps before attempt zero.

- [x] **Step 3: Move the delay after attempt zero**

```typescript
for (let attempt = 0; attempt < COMMAND_RESULT_POLL_ATTEMPTS; attempt += 1) {
    if (attempt > 0) {
        await new Promise<void>(resolve => setTimeout(resolve, COMMAND_RESULT_POLL_INTERVAL_MS));
    }
    if (signal?.aborted) return lastPending;
    // existing canonical lookup and result handling
}
```

- [x] **Step 4: Run the focused Vitest files and verify GREEN**

Run the Step 2 command. Expected: both files pass with zero failures.

---

### Task 5: Update operations documentation and run final gates

**Files:**
- Modify: `docs/admin/server-lifecycle.md`
- Modify: `docs/superpowers/plans/2026-09-05-reserved-turn-deadline-scheduler.md`
- Verify: all files changed by Tasks 1-4

**Interfaces:**
- Documents: 250 ms observation bound, per-general deadline execution, unchanged five-minute world cadence, and monitoring-only health cron.

- [x] **Step 1: Update the admin lifecycle documentation**

Add an operational section stating:

```markdown
- `opensamguk.daemon.idle-poll-ms` defaults to 250 ms and bounds command/deadline observation latency.
- General reserved turns execute at their own persisted `turnTime`; they do not advance the world calendar.
- `tickSeconds` remains the global world/month boundary cadence (300 seconds in the current production world).
- The five-minute GitHub Actions daemon-health schedule monitors the loop; it does not execute turns.
```

- [x] **Step 2: Mark every completed plan checkbox**

Change each executed `- [ ]` item in this plan to `- [x]` only after its command has produced the expected result.

- [x] **Step 3: Run backend focused verification**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests opensamguk.engine.turn.DrainTailAdvanceTest \
  --tests opensamguk.engine.run.TurnDaemonRunnerTest \
  --tests opensamguk.engine.run.TurnRunServiceIT \
  --tests opensamguk.engine.run.TurnRunServiceFlushRecoveryTest --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; inspect XML for zero failures and only documented Docker skips.

- [x] **Step 4: Run frontend verification**

```bash
cd web
pnpm --filter @opensamguk/web-game exec vitest run \
  __tests__/commandSubmit.test.ts \
  __tests__/commandSubmit.result-events.test.ts \
  --maxWorkers=1
pnpm --filter @opensamguk/web-game typecheck
```

Expected: 13 or more focused tests pass and TypeScript exits zero.

- [ ] **Step 5: Run repository guards and inspect the diff** *(focused guards passed; the full Docker-backed module run was stopped after Testcontainers hung waiting for Docker container creation)*

```bash
git diff --check
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:game-engine:test :app:game-api:test --rerun-tasks
git status --short
git diff --stat origin/main...HEAD
```

Expected: no whitespace errors, backend module tests succeed, and only scheduler/client/docs files from this plan are changed.

- [x] **Step 6: Commit the logical implementation**

```bash
git add app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnDaemonLifecycle.kt \
  app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt \
  app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonRunner.kt \
  app/game-engine/src/test/kotlin/opensamguk/engine/turn/DrainTailAdvanceTest.kt \
  app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnRunServiceIT.kt \
  app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnRunServiceFlushRecoveryTest.kt \
  app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnDaemonRunnerTest.kt \
  web/game/lib/api.ts web/game/__tests__/commandSubmit.test.ts \
  web/game/__tests__/commandSubmit.result-events.test.ts \
  docs/admin/server-lifecycle.md \
  docs/superpowers/plans/2026-09-05-reserved-turn-deadline-scheduler.md
git commit -m $'fix: execute reserved turns on personal deadlines\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>'
```

Expected: one implementation commit after the design commit, with no uncommitted planned changes.
