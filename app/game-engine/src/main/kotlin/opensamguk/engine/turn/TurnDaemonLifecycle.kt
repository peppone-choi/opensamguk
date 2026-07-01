package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.tick.ServerClock
import java.time.Duration
import java.time.Instant

/**
 * P1 Task F3 — minimal daemon lifecycle: resolve the next run time, list the generals DUE this
 * tick, and drive [ReservedTurnHandler] once per due general.
 *
 * **Processed-count gated, NOT wall-clock** (research §1e / N5): a wall-clock budget can flush
 * mid-turn and produce a DB state the PHP golden never had. P1 drains ALL due generals in one pass
 * (no partial checkpoint) so the golden compares at a clean turn boundary. The caller flushes the
 * recorder's accumulated patches ONCE after the drain (Task F4/F5), never mid-pass.
 *
 * A "due" general is one whose [TurnGeneral.turnTime] is at/after the resolved run time of the
 * current tick. Generals are processed in ascending `turnTime`, then ascending id (a stable,
 * deterministic order so a parity replay visits them in the same sequence every run).
 */
class TurnDaemonLifecycle(
    private val world: InMemoryTurnWorld,
    private val handler: ReservedTurnHandler,
    /**
     * P5 FM2 — the NATION-command resolve path (R-SEAM §4). When supplied, the officer_level>=5 nation
     * pass runs BEFORE the general pass for a chief general (`hasNationTurn`, R-SEAM §2). Null = no nation
     * pass (the P1–P4 general-only call sites stay unchanged).
     */
    private val nationProcessor: ProcessNationCommand? = null,
    /**
     * How the lifecycle obtains the reserved `(actionCode, argJson)` for a chief's nation ring slot
     * (`nation_turn` keyed `(nationId, officerLevel, turn_idx=0)`, PHP `:263-267`). Returns 휴식 when no
     * nation command is reserved. Only consulted when [nationProcessor] is set AND `hasNationTurn`.
     */
    private val reservedNationActionOf: (nationId: Int, officerLevel: Int) -> ReservedTurn = { _, _ -> ReservedTurn("휴식", "") },
    /**
     * P5 FM2 — the NATION-pass AI interpose (R-SEAM §2 `:305-308`). For an AI-controlled chief
     * (`npc >= 2`) with `use_auto_nation_turn` truthy, this REPLACES the reserved nation command with the
     * AI's `chooseNationTurn(...)` result BEFORE [ProcessNationCommand.process]. **Only `chooseNationTurn`
     * is wired — `chooseInstantNationTurn` is NOT (decision #3 / B3 / R-SEAM §3).** Null = a human chief
     * runs the reserved nation command verbatim (no AI).
     */
    private val chooseNationTurn: ((generalId: Int, reserved: ReservedTurn) -> ChosenCommand)? = null,
    /**
     * P5 ONE-RNG — the per-general AI decision-window OPEN (PHP `:290/294` `$ai = new GeneralAI($general)`,
     * ONE `GeneralAI` per general per turn). Invoked ONCE per due general — after `processBlocked` returns
     * false, BEFORE the nation pass — so a due general's nation pass + general pass thread the SAME per-general
     * `"GeneralAI"` decision rng (the [AiTurnAdapter] cache keyed `(generalId, year, month)`: `chooseNationTurn`
     * builds it as the stream PREFIX, `chooseGeneralTurn` CONTINUES it). The daemon wires this to
     * [AiTurnAdapter.beginGeneralTurn] so production matches the gated `AiSelectionGateIT` (which calls
     * `resetRngFor(gid)` at the same boundary). Default = no-op (the P1–P4 general-only call sites + any
     * caller that recreates the adapter per turn stay unchanged). The execution streams
     * (`'nationCommand'`/`'generalCommand'`) are re-seeded at resolve and stay DISTINCT from this stream
     * (R-SEAM §2).
     */
    private val beginGeneralTurn: (generalId: Int) -> Unit = { },
    /**
     * The killturn baseline / clock-freeze gate (`processBlocked`, PHP `:299`). Supplies the
     * [LifecycleEnv] the SINGLE `processBlocked()` gate reads (the block log's `<1>HH:MM</>` date stamp +
     * the killturn decrement). Default builds a minimal env from the world state + the tick's turn time.
     */
    private val lifecycleEnvOf: (state: TurnWorldState, date: String) -> LifecycleEnv = { state, date ->
        LifecycleEnv(baselineKillturn = 0, year = state.currentYear, month = state.currentMonth, turnTerm = 1, turnTimeHm = date)
    },
    /**
     * PHP pulls both command rings after each due general, outside the command-condition block:
     * `pullNationCommand(...)` then `pullGeneralCommand(...)`
     * (`TurnExecutionHelper.php:350-351`). Keep these callbacks separate from [reservedActionOf]
     * so tests can record the lifecycle order while production wires the JDBC ring repository.
     */
    private val pullNationTurnOf: (nationId: Int, officerLevel: Int) -> Unit = { _, _ -> },
    private val pullGeneralTurnOf: (generalId: Int) -> Unit = { _ -> },
    /**
     * How the lifecycle obtains the reserved `(actionCode, argJson)` for a due general (the
     * `general_turn` ring / enqueued command). Widened from `(Int)->String` to carry the stored `arg`
     * jsonb (R-SEAM §1 / FM1) — the seed still keys on `definition.key`, so the widening only feeds the
     * resolver's arg map; targeted reserved commands (이동/발령/…) now reach the resolver with their arg.
     *
     * Declared LAST so the trailing-lambda call form `TurnDaemonLifecycle(world, handler) { reservedActionOf }`
     * (the P1–P4 general-only call sites) binds the lambda to THIS param while the FM2 nation collaborators
     * above stay defaulted.
     */
    private val reservedActionOf: (generalId: Int) -> ReservedTurn,
) {

    /** Resolve the next run time: the previous run time + the world's tick interval. */
    fun nextRunTime(): Instant {
        val state = world.getState()
        return state.lastTurnTime.plus(Duration.ofSeconds(state.tickSeconds.toLong()))
    }

    /**
     * The generals due at [runTime], in deterministic order (ascending `turnTime`, then ascending id).
     *
     * PHP 선택 게이트(`TurnExecutionHelper.php:237`)는 `WHERE turntime < %s`(STRICT `<`)다 — `runTime`과
     * 정확히 같은 turntime은 아직 due가 아니다. 이전 버그는 `!isAfter`(≤, INCLUSIVE)라서 경계 턴을 한 틱
     * 일찍 끌어들였다. 이제 `isBefore`(strict <)로 PHP와 draw-순서 동일하게 맞춘다.
     */
    fun dueGenerals(runTime: Instant): List<TurnGeneral> =
        world.listGenerals()
            .filter { it.turnTime.isBefore(runTime) }
            .sortedWith(compareBy({ it.turnTime }, { it.id }))

    /**
     * Drain ALL generals due at [runTime] through the handler, in one pass (no mid-pass flush).
     * Returns the per-general outcomes in processed order. The `year`/`month`/`date` come from the
     * world state (the turn the tick resolves).
     */
    fun runTick(runTime: Instant = nextRunTime()): List<ReservedTurnHandler.HandledTurn> {
        val state = world.getState()
        val date = formatTurnTime(runTime)
        val due = dueGenerals(runTime)
        val env = lifecycleEnvOf(state, date)
        val handled = ArrayList<ReservedTurnHandler.HandledTurn>(due.size)
        for (g in due) {
            // The SINGLE processBlocked() gate (PHP `:299`): `block>=2` skips the WHOLE command block —
            // BOTH the nation pass AND the general pass (R-SEAM §2). The handler's processBlocked pushes
            // the block log + decrements killturn, then only the COMMAND block is skipped. PHP still pulls
            // the rings and calls updateTurnTime afterward (`TurnExecutionHelper.php:350-363`), so do not
            // `continue` here.
            val blocked = handler.processBlocked(g.id, env)
            if (!blocked) {
                // OPEN this due general's per-general "GeneralAI" decision window (PHP `:290/294` `new GeneralAI`).
                // Invoked ONCE per general, BEFORE the nation pass, so the nation pass (`chooseNationTurn`, stream
                // PREFIX) and the general pass (`chooseGeneralTurn`, continuation) share ONE per-general decision
                // rng — matching the gated AiSelectionGateIT. No-op by default. The execution rngs are re-seeded
                // downstream and stay DISTINCT (R-SEAM §2).
                beginGeneralTurn(g.id)

                // --- NATION PASS FIRST (R-SEAM §2 `:301-324`), under the same processBlocked() gate ---
                // hasNationTurn ⇐ nation!=0 && officer_level>=5 (PHP `:260`). Only when a nation processor is
                // wired. The AI hook (chooseNationTurn ONLY — chooseInstantNationTurn is NOT wired, decision #3)
                // replaces the reserved nation command BEFORE the resolve; a human chief runs it verbatim.
                if (nationProcessor != null && hasNationTurn(g)) {
                    val reservedNation = reservedNationActionOf(g.nationId, g.officerLevel)
                    var nationCmd = ChosenCommand(reservedNation.actionCode, ReservedTurnHandler.decodeArgs(reservedNation.argJson))
                    if (chooseNationTurn != null && ReservedTurnHandler.isAiControlled(g) && useAutoNationTurn(g)) {
                        nationCmd = chooseNationTurn.invoke(g.id, reservedNation)
                    }
                    val lastTurn = lastNationTurnOf(g.nationId, g.officerLevel)
                    nationProcessor.process(
                        generalId = g.id,
                        officerLevel = g.officerLevel,
                        nationCommand = nationCmd,
                        lastTurn = lastTurn,
                        year = state.currentYear,
                        month = state.currentMonth,
                        date = date,
                    )
                }

                // --- GENERAL PASS SECOND (R-SEAM §2 `:326-348`) — the existing handler interpose ---
                val reserved = reservedActionOf(g.id)
                val result = handler.handle(
                    generalId = g.id,
                    reserved = reserved,
                    year = state.currentYear,
                    month = state.currentMonth,
                    date = date,
                )
                handled.add(result)

                // ── 1) killturn 감소/리셋 (PHP processCommand 꼬리, `TurnExecutionHelper.php:153-165`) ──
                // PHP는 processCommand 안에서 command.run() 직후 killturn을 처리한다(:348→:153). Kotlin handle()는
                // 이 꼬리를 제외한 processCommand 본체이므로, handle() 직후 applyKillturnDecrement를 호출해 PHP
                // 순서(run → killturn → updateTurnTime)를 그대로 재현한다.
                //
                // commandClassName(PHP `$commandClassName = $commandObj->getName()`, :118/:147):
                //  - 명령 정상 실행(fellBack=false): 실제로 resolve된 명령 이름 = result.definition.name.
                //  - 거부/폴백(fellBack=true): PHP는 commandClassName을 휴식으로 되돌리지 않고 예약 명령 이름을
                //    그대로 유지한다(while 루프가 hasFullConditionMet 실패 시 break, 이름 미변경 — :121-126).
                //    killturn 분기는 오직 `== '휴식'` 여부만 보므로, 예약 actionCode가 리터럴 "휴식"일 때만 휴식이고
                //    그 외 거부된 비-휴식 명령은 비-휴식으로 남아야 한다 → reserved.actionCode를 그대로 쓴다(휴식
                //    명령의 actionCode만 "휴식"으로 resolve되므로 byte-정확). AI 교체(autorunMode=true)는 :159
                //    분기에서 commandClassName과 무관하게 감소하므로 영향 없음.
                //
                // autorunMode는 PER-GENERAL 신호다(PHP `$autorunMode`, :333-336 — AI가 예약 명령을 다른 명령으로
                // 교체했을 때만 true). handle()이 HandledTurn.autorunMode로 노출하므로, 틱-레벨 env를 그 값으로
                // copy해 :159 autorun 분기가 정확히 동작하게 한다(틱 env의 autorunMode 기본 false는 비-AI/AI-동일예약).
                val commandClassName = if (result.fellBack) reserved.actionCode else result.definition.name
                handler.applyKillturnDecrement(g.id, commandClassName, env.copy(autorunMode = result.autorunMode))
            }

            // ── 1b) command ring pull (PHP `TurnExecutionHelper.php:350-351`) ──
            // This is intentionally outside the `!blocked` command block: a blocked turn and a failed
            // reserved command both consume one visible row in the turn table.
            pullNationTurnOf(g.nationId, g.officerLevel)
            pullGeneralTurnOf(g.id)

            // ── 2) updateTurnTime (PHP `:170-230`, 호출부 `:363`) ──
            // lived_month+1 → killturn<=0 kill/유체이탈 게이트 → age>=retirementYear 환생 게이트 →
            // `turntime = addTurn(turntime, turnterm)`. KILLED면 updateTurnTime 내부에서 turntime을 advance하지
            // 않고 일찍 return하므로(:204/:437), 여기서 별도 분기 없이 한 번만 호출한다(이중 처리 금지).
            handler.updateTurnTime(g.id, env)
        }
        return handled
    }

    /** PHP `:260` — `hasNationTurn ⇐ $general->getVar('nation') != 0 && officer_level >= 5`. */
    private fun hasNationTurn(g: TurnGeneral): Boolean = g.nationId != 0 && g.officerLevel >= 5

    /** PHP `:305` — `$general->getAuxVar('use_auto_nation_turn') ?? 1` (the pre-turn snapshot; truthy default). */
    private fun useAutoNationTurn(g: TurnGeneral): Boolean =
        (g.meta["use_auto_nation_turn"] as? Number)?.toInt()?.let { it != 0 } ?: true

    /** PHP `:271` — `LastTurn::fromRaw($nationStor->getValue("turn_last_{officer_level}"))` (the chief ring slot). */
    private fun lastNationTurnOf(nationId: Int, officerLevel: Int): LastTurn {
        val raw = world.getNationById(nationId)?.meta?.get("turn_last_$officerLevel")
        @Suppress("UNCHECKED_CAST")
        return LastTurn.fromRaw(raw as? Map<String, Any?>)
    }

    private fun formatTurnTime(at: Instant): String {
        val local = at.atZone(ServerClock.SERVER_ZONE).toLocalTime()
        return "%02d:%02d".format(local.hour, local.minute)
    }

    /**
     * FT3 — the `executeAllCommand` two-level month-boundary loop driver (PHP
     * `TurnExecutionHelper.php:393-517`).
     *
     * This is the OUTER orchestrator that wraps the P2 single-pass per-general drain
     * ([TurnDaemonLifecycle.runTick] / [dueGenerals], `compareBy(turnTime,id)` ==
     * `ORDER BY turntime ASC, no ASC`) and interleaves ONE [MonthlyPipeline][opensamguk.logic.tick.MonthlyPipeline]
     * run per crossed month boundary. It is PURE/in-memory — the daemon supplies the two callbacks
     * ([drain] = the per-general pass; [runMonth] = `MonthlyPipeline.runMonth`), and flushes ONCE per
     * boundary (the P2 clean-boundary contract; the monthly bulk writes are recorded as
     * `ChangeRecorder` deltas alongside the per-general deltas, preserving the single-dirty-source
     * invariant — P2 Risk #4 — across the monthly batch).
     *
     * **Clean-boundary / processed-count model (consolidated OQ #4):** PHP's wall-clock budget can
     * partial-checkpoint mid-pass; we do NOT port that. We drain ALL due generals so the golden
     * compares at a clean phase boundary (design §11 implies a clean boundary).
     *
     * The loop:
     * - `now < turntime` → no-op (the next turn has not arrived).
     * - `isUnitedState ∈ {2,3}` → freeze the whole tick (천통 — unification settled/locked).
     * - `prevTurn = cutTurn(turntime)`, `nextTurn = addTurn(prevTurn)`; `while (nextTurn <= now)`:
     *   **L1** [drain] all generals with `turnTime < nextTurn` (the P2 pass), **L2** optionally
     *   [runMonth] at `nextTurn` (`MonthlyPipeline.runMonth`; currently only when the new phase is 상순),
     *   **L11** advance `prevTurn=nextTurn`,
     *   `nextTurn=addTurn(prevTurn)`.
     * - After the loop: a FINAL sub-phase [drain] at `now` (the partial phase since the last
     *   boundary), then the daemon flushes.
     *
     * @return the number of monthly pipeline runs (0 when no-op / frozen).
     */
    class MonthBoundaryDriver(
        /** The per-general drain pass for all generals due strictly before the given instant. */
        private val drain: (upto: Instant) -> Unit,
        /** `MonthlyPipeline.runMonth` for the month whose boundary is the given `nextTurn`. */
        private val runMonth: (nextTurn: Instant) -> Unit,
        private val runMonthWhen: (nextTurn: Instant) -> Boolean = { true },
    ) {
        fun run(turntime: Instant, now: Instant, turnTerm: Int, isUnitedState: Int): Int {
            if (now.isBefore(turntime)) return 0           // next turn not yet arrived
            if (isUnitedState == 2 || isUnitedState == 3) return 0 // 천통 freeze

            var prevTurn = ServerClock.cutTurn(turntime, turnTerm)
            var nextTurn = ServerClock.addTurn(prevTurn, turnTerm)
            var crossed = 0
            while (!nextTurn.isAfter(now)) {
                drain(nextTurn)        // L1 — drain all generals with turnTime < nextTurn
                if (runMonthWhen(nextTurn)) {
                    runMonth(nextTurn) // L2 — the monthly 6-step pipeline
                    crossed++
                }
                prevTurn = nextTurn    // L11 — advance the boundary
                nextTurn = ServerClock.addTurn(prevTurn, turnTerm)
            }
            // Final sub-month drain of the partial month since the last crossed boundary.
            drain(now)
            return crossed
        }
    }
}
