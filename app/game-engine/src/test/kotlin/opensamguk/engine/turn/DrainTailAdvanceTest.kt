package opensamguk.engine.turn

import opensamguk.common.constants.EffectiveGameConst
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.ServerClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loop-parity LC-DRAIN — the PRODUCTION per-general drain ([TurnDaemonLifecycle.runTick]) must run the
 * per-general lifecycle TAIL after each general's command, exactly as PHP does.
 *
 * Port target (`TurnExecutionHelper.php` per-general loop `:246-365`):
 *  - `:348` processCommand(run) → `:153-165` killturn 감소/리셋 (processCommand 꼬리)
 *  - `:363` updateTurnTime() → `:170-230`: lived_month+1, killturn<=0 kill/유체이탈 게이트,
 *    age>=retirementYear 환생 게이트, 그리고 `turntime = addTurn(turntime, turnterm)`
 *  - 선택 게이트 `:237` `WHERE turntime < %s`(STRICT `<`).
 *
 * 이전 버그: runTick이 handler.handle()만 호출하고 꼬리(applyKillturnDecrement/updateTurnTime)를 NEVER
 * 호출 → per-general turnTime이 advance되지 않아 dueGenerals가 매 틱 같은 장수를 재선택(예약 명령 매 틱
 * 재실행), killturn 미감소, kill/유체이탈/환생 미발동. 이 테스트는 production drain이 그 꼬리를 실제로
 * 돌리는지(turnTime advance + killturn 감소 + 다음 틱 재선택 안 됨)를 단언한다.
 *
 * 하네스 스타일은 [LifecycleTailTest]/[KillTombstoneTest]/[RebirthAndRingTest]를 미러링한다. runTick은
 * production [DaemonLoopConfig]와 동일한 LifecycleEnv 산식(turnTerm=tickSeconds/60, baselineKillturn=
 * EffectiveGameConst.killturn(turnterm, npcmode=0))을 그대로 쓰도록 lifecycleEnvOf를 주입한다.
 */
class DrainTailAdvanceTest {

    private val t0 = Instant.parse("0200-06-15T14:00:00Z")
    private val tickSeconds = 3600 // turnterm = 60분
    private val turnTerm = tickSeconds / 60 // = 60 (= PHP $gameStor->turnterm)

    // production DaemonLoopConfig.lifecycleEnvOf와 동일한 산식.
    private fun lifecycleEnvOf(state: TurnWorldState, date: String): LifecycleEnv {
        val tt = state.tickSeconds / 60
        return LifecycleEnv(
            baselineKillturn = EffectiveGameConst.killturn(tt, npcmode = 0),
            year = state.currentYear,
            month = state.currentMonth,
            turnTerm = tt,
            isunited = state.meta["isunited"] as? Int ?: 0,
            turnTimeHm = date,
        )
    }

    private fun gen(
        id: Int = 1,
        npc: Int = 0,
        age: Int = 30,
        killturn: Int = 5,
        turnTime: Instant = t0,
        block: Int = 0,
        deadyear: Int = 999,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        age = age,
        npcState = npc,
        turnTime = turnTime,
        meta = linkedMapOf<String, Any?>(
            "killturn" to killturn,
            "block" to block,
            "deadyear" to deadyear,
            "lived_month" to 100,
        ),
    )

    private fun world(vararg generals: TurnGeneral) =
        InMemoryTurnWorld(
            WorldSnapshot(
                // tickSeconds=3600 → turnterm 60; t0가 lastTurnTime, nextRunTime은 t0 + 1시간(strict 미래).
                state = TurnWorldState(1, 200, 6, tickSeconds, t0),
                generals = generals.toList(),
                // 장수 cityId=5 → handle()의 명령 resolve가 도시를 읽으므로 city 5를 월드에 넣는다.
                cities = listOf(City(id = 5, name = "c5", nationId = 1, level = 5, meta = linkedMapOf("trust" to 50.0))),
                nations = listOf(Nation(1, "n1", "#000")),
            ),
        )

    private fun lifecycle(
        world: InMemoryTurnWorld,
        reservedActionOf: (Int) -> ReservedTurn = { ReservedTurn("휴식", "") },
        pullNationTurn: (Int, Int) -> Unit = { _, _ -> },
        pullGeneralTurn: (Int) -> Unit = { },
    ): TurnDaemonLifecycle {
        val handler = ReservedTurnHandler(
            world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "0".repeat(32),
            startYear = 184,
        )
        return TurnDaemonLifecycle(
            world = world,
            handler = handler,
            lifecycleEnvOf = ::lifecycleEnvOf,
            pullNationTurnOf = pullNationTurn,
            pullGeneralTurnOf = pullGeneralTurn,
            reservedActionOf = reservedActionOf,
        )
    }

    private fun killturnOf(world: InMemoryTurnWorld, id: Int): Int =
        (world.getGeneralById(id)!!.meta["killturn"] as Number).toInt()

    @Test
    fun `runTick advances a due general's turnTime by addTurn(turnTerm)`() {
        val w = world(gen(id = 1, killturn = 5))
        val lc = lifecycle(w)

        // production은 nextRunTime() = lastTurnTime + tickSeconds (장수 turntime=t0보다 STRICT 미래)를 쓴다.
        lc.runTick() // default = nextRunTime()

        val g = w.getGeneralById(1)!!
        // updateTurnTime의 `turntime = addTurn(turntime, turnterm)` (PHP :218).
        assertEquals(
            ServerClock.addTurn(t0, turnTerm, 1),
            g.turnTime,
            "drain이 per-general 꼬리를 돌려 turnTime을 addTurn(turnTerm)만큼 advance해야 한다",
        )
    }

    @Test
    fun `runTick decrements killturn for a 휴식 (rest) reserved general`() {
        val w = world(gen(id = 1, killturn = 5))
        val lc = lifecycle(w)

        lc.runTick()

        // 예약=휴식 → applyKillturnDecrement의 `commandClassName == 휴식` 분기 (:161-162) → killturn -1.
        assertEquals(4, killturnOf(w, 1), "휴식 예약 장수는 killturn이 1 감소해야 한다 (processCommand 꼬리 :153-165)")
    }

    @Test
    fun `runTick consumes the ring for an NPC general too`() {
        val pullNationCalls = AtomicInteger(0)
        val pullGeneralCalls = AtomicInteger(0)
        val w = world(gen(id = 1, npc = 2, killturn = 5))
        val handler = ReservedTurnHandler(
            w,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "0".repeat(32),
            startYear = 184,
            aiHook = { _, reserved -> ChosenCommand(reserved.actionCode, emptyMap()) },
        )
        val lc = TurnDaemonLifecycle(
            world = w,
            handler = handler,
            lifecycleEnvOf = ::lifecycleEnvOf,
            pullNationTurnOf = { nationId, officerLevel -> pullNationCalls.incrementAndGet() },
            pullGeneralTurnOf = { pullGeneralCalls.incrementAndGet() },
        ) { ReservedTurn("che_농지개간", "") }

        lc.runTick()

        assertEquals(1, pullNationCalls.get(), "NPC general should still rotate the nation ring")
        assertEquals(1, pullGeneralCalls.get(), "NPC general should still rotate the general ring")
    }

    @Test
    fun `runTick advances lived_month by 1 (updateTurnTime L278 inheritance)`() {
        val w = world(gen(id = 1, killturn = 5))
        val lc = lifecycle(w)

        lc.runTick()

        val g = w.getGeneralById(1)!!
        assertEquals(101, (g.meta["lived_month"] as Number).toInt(), "updateTurnTime은 lived_month를 +1 한다")
    }

    @Test
    fun `runTick pulls command rings after a successful reserved command`() {
        val pulledGenerals = mutableListOf<Int>()
        val pulledNations = mutableListOf<Pair<Int, Int>>()
        val w = world(gen(id = 1, killturn = 5))
        val lc = lifecycle(
            world = w,
            pullNationTurn = { nationId, officerLevel -> pulledNations += nationId to officerLevel },
            pullGeneralTurn = { generalId -> pulledGenerals += generalId },
        )

        lc.runTick()

        assertEquals(listOf(1), pulledGenerals, "처리된 장수의 general_turn 0번 슬롯을 소비해야 한다")
        assertEquals(listOf(1 to 1), pulledNations, "PHP처럼 nation_turn도 같은 루프 꼬리에서 한 칸 당긴다")
    }

    @Test
    fun `runTick pulls the general ring even when the reserved command fails constraints`() {
        val pulledGenerals = mutableListOf<Int>()
        val w = world(gen(id = 1, killturn = 5))
        val lc = lifecycle(
            world = w,
            reservedActionOf = { ReservedTurn("che_건국", """{"nationName":"n","nationType":"che_중립","colorType":0}""") },
            pullGeneralTurn = { generalId -> pulledGenerals += generalId },
        )

        val handled = lc.runTick()

        assertEquals(1, handled.size, "조건 실패도 장수 턴 하나를 처리한 것으로 기록된다")
        assertTrue(handled.single().fellBack, "군주가 아닌 장수의 건국 예약은 조건 실패로 휴식 폴백이어야 한다")
        assertEquals(listOf(1), pulledGenerals, "조건 실패 후에도 general_turn은 한 칸 위로 당겨야 한다")
    }

    @Test
    fun `runTick pulls rings and advances turnTime for a blocked general`() {
        val pulledGenerals = mutableListOf<Int>()
        val w = world(gen(id = 1, killturn = 5, block = 2))
        val lc = lifecycle(
            world = w,
            pullGeneralTurn = { generalId -> pulledGenerals += generalId },
        )

        val handled = lc.runTick()

        assertTrue(handled.isEmpty(), "블럭 장수는 명령 블록 자체를 실행하지 않는다")
        assertEquals(listOf(1), pulledGenerals, "블럭으로 스킵되어도 PHP 루프 꼬리에서 예약 슬롯은 소비된다")
        assertEquals(ServerClock.addTurn(t0, turnTerm, 1), w.getGeneralById(1)!!.turnTime)
    }

    @Test
    fun `a general is NOT re-selected by dueGenerals on the next tick after its turnTime advanced`() {
        // 이 테스트가 핵심 회귀 가드다: 꼬리가 없던 버그에서는 turnTime이 advance되지 않아 dueGenerals가
        // 매 틱 같은 장수를 재선택했다(예약 명령 매 틱 재실행). 꼬리가 돌면 advance된 turnTime이 다음
        // nextRunTime보다 미래라 더 이상 due가 아니어야 한다.
        val w = world(gen(id = 1, killturn = 5))
        val lc = lifecycle(w)

        // 틱 1: 장수 due → 처리 → turnTime = t0 + 60분 = addTurn(t0).
        val firstRun = lc.nextRunTime() // t0 + tickSeconds(=1시간)
        val handled1 = lc.runTick(firstRun)
        assertEquals(1, handled1.size, "틱 1: due 장수 1명이 처리되어야 한다")

        val advanced = w.getGeneralById(1)!!.turnTime
        assertEquals(ServerClock.addTurn(t0, turnTerm, 1), advanced, "turnTime이 addTurn으로 advance됨")

        // 틱 2: lastTurnTime을 한 틱 전진시키고 동일하게 nextRunTime을 계산한다. advance된 turnTime이
        // 다음 nextRunTime과 같거나(같으면 strict `<`로 due 아님) 미래이면 재선택되지 않아야 한다.
        // 여기서는 advanced turnTime(t0+60분)이 firstRun(t0+60분)과 동일하므로, STRICT `<` 게이트(:237)가
        // 이를 due에서 배제해야 한다(이전 INCLUSIVE `<=` 버그였다면 재선택되어 실패).
        val due2 = lc.dueGenerals(firstRun)
        assertFalse(
            due2.any { it.id == 1 },
            "advance된 turnTime이 drain 경계와 같으면 STRICT `<` 게이트(PHP :237 `turntime < %s`)로 재선택되지 않아야 한다",
        )

        // 한 틱 더 미래(t0 + 2시간)에서야 비로소 다시 due가 된다(turntime t0+60분 < t0+120분).
        val nextNextRun = firstRun.plus(Duration.ofSeconds(tickSeconds.toLong()))
        val due3 = lc.dueGenerals(nextNextRun)
        assertTrue(due3.any { it.id == 1 }, "경계가 advance된 turnTime을 STRICT 추월하면 다시 due가 된다")
    }

    @Test
    fun `dueGenerals uses STRICT less-than - a general exactly at the boundary is NOT due`() {
        // BUG #6 가드: PHP `:237` `WHERE turntime < %s`(STRICT). turnTime == runTime은 due가 아니다.
        val w = world(gen(id = 1, turnTime = t0, killturn = 5))
        val lc = lifecycle(w)

        // 경계를 정확히 t0로 두면(=장수 turntime) due가 아니어야 한다.
        assertTrue(lc.dueGenerals(t0).isEmpty(), "turnTime == boundary는 STRICT `<`로 due 아님 (BUG #6 fix)")
        // 경계가 t0보다 STRICT 미래면 due다.
        assertTrue(lc.dueGenerals(t0.plusSeconds(1)).any { it.id == 1 }, "turnTime < boundary면 due")
    }
}
