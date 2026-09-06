package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.plan.BattleReplayCodec
import opensamguk.logic.war.plan.BattleReplayDraft
import opensamguk.logic.war.plan.PlanStop
import opensamguk.logic.war.plan.PlannedWarBattleHooks
import opensamguk.logic.war.plan.ReplayRecordingHooks
import opensamguk.logic.war.plan.SealedBattlePlan
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Phase 4X-C spec v4.1 §8 **적색 프로브** — (a) 계획 없음: 훅 호출 기록(`onBattleResultLog` 포함)·draw 스트림·종료 상태가
 * 리팩터 전 기대(= 오늘 동작)와 deep-equal, (b) 계획(pct 10 / probe)이 있으면 더 이른 퇴각으로 달라짐, (c) 두 번 = 같은
 * `replay_hash`, (d) M1: 비공성 성이 `def` 인 페이즈의 계획 정지도 `addLose`·`tryWound` 비용을 치르고 `retreat` 다.
 */
class ProcessWarPlanHookTest {

    private class RecordingRng(seed: ByteArray, val log: MutableList<String>) : RandUtil(LiteHashDrbg(seed)) {
        override fun nextRange(min: Double, max: Double): Double { log.add("nextRange($min,$max)"); return super.nextRange(min, max) }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int { log.add("nextRangeInt($minInclusive,$maxInclusive)"); return super.nextRangeInt(minInclusive, maxInclusive) }
        override fun nextBool(prob: Double): Boolean { log.add("nextBool($prob)"); return false }
    }

    /** 모든 훅 호출을 기록한다(순서 핀). plannedStop 은 기본(null). */
    private class RecordingHooks(val calls: MutableList<String>) : WarBattleHooks {
        override fun battleInitCaller(unit: WarUnit): WarUnitTriggerCaller? { calls += "init:${unit.getUnitId()}"; return WarUnitTriggerCaller() }
        override fun battlePhaseCaller(unit: WarUnit): WarUnitTriggerCaller? { calls += "phaseCaller:${unit.getUnitId()}"; return WarUnitTriggerCaller() }
        override fun addTrain(unit: WarUnit, amount: Int) { calls += "addTrain:${unit.getUnitId()}" }
        override fun onAdvanceLog(attacker: WarUnitGeneral, city: WarUnitCity) { calls += "advance" }
        override fun onContactLog(attacker: WarUnitGeneral, defender: WarUnit) { calls += "contact:${defender.getUnitId()}" }
        override fun onPhaseLog(attacker: WarUnitGeneral, defender: WarUnit, deadAttacker: Int, deadDefender: Int) { calls += "phase:${defender.getUnitId()}:$deadAttacker:$deadDefender" }
        override fun onBattleResultLog(unit: WarUnit) { calls += "result:${unit.getUnitId()}" }
        override fun onRetreatLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) { calls += "retreat:${defender.getUnitId()}:$noRice" }
        override fun onDefenderDownLog(attacker: WarUnitGeneral, defender: WarUnit, noRice: Boolean) { calls += "down:${defender.getUnitId()}" }
        override fun addConflict(city: WarUnitCity, attacker: WarUnitGeneral): Boolean { calls += "conflict"; return false }
    }

    private val seed = ByteArray(32) { it.toByte() }
    private val pipeline = GeneralActionPipeline()
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!

    private fun general(id: Int, crew: Int = 5000, rice: Int = 10000): General = General(
        id = id, nationId = 1, cityId = 10, leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 1000, rice = rice,
        crew = crew, train = 100.0, atmos = 100.0, crewTypeId = 1100, meta = linkedMapOf("explevel" to 0),
    )

    private fun warGeneral(g: General, attacker: Boolean, rng: RandUtil): WarUnitGeneral =
        WarUnitGeneral(rng = rng, state = WarUnitGeneralState(g), pipeline = pipeline, crewType = footman, tech = 0, isAttacker = attacker, cityLevel = 9, isCapital = false)

    private fun warCity(rng: RandUtil): WarUnitCity = WarUnitCity(
        rng = rng, year = 200, startYear = 180,
        state = WarUnitCityState(City(id = 10, nationId = 0, level = 5, commerce = 1000, commerceMax = 9999, agriculture = 1000, agricultureMax = 9999, supplyState = 1, frontState = 0, trust = 100.0, security = 1000, securityMax = 9999, defense = 500, defenseMax = 9999, wall = 500, wallMax = 9999, population = 100000, populationMax = 999999)),
    )

    private data class Run(val calls: List<String>, val draws: List<String>, val attackerCrew: Int, val defenderCrews: List<Int>, val conquer: Boolean, val draft: BattleReplayDraft?)

    private fun draft(plan: SealedBattlePlan, crewBefore: Int = 5000) = BattleReplayDraft(1, 1, 10, 0, "seed", plan, crewBefore, 10000, "in")

    /** 두 수비 장수, 공격자는 병력이 넉넉해 자연 퇴각이 없다(페이즈 소진 또는 계획 정지). */
    private fun run(plan: SealedBattlePlan?, cityFirst: Boolean = false): Run {
        val draws = mutableListOf<String>(); val calls = mutableListOf<String>()
        val rng = RecordingRng(seed, draws)
        val attacker = warGeneral(general(1, crew = 50000), attacker = true, rng = rng)
        val d1 = warGeneral(general(2, crew = 3000), attacker = false, rng = rng)
        val d2 = warGeneral(general(3, crew = 3000), attacker = false, rng = rng)
        val city = warCity(rng)
        val base = RecordingHooks(calls)
        val draft = plan?.let { draft(it, attacker.getCrew()) }
        val hooks: WarBattleHooks = if (plan != null) ReplayRecordingHooks(PlannedWarBattleHooks(base, plan, attacker.getCrew()), draft!!) else base
        val queue = ArrayDeque<WarUnit>(if (cityFirst) listOf(city, d1) else listOf(d1, d2))
        val getNextDefender: (WarUnit?, Boolean) -> WarUnit? = { _, reqNext -> if (reqNext) queue.removeFirstOrNull() else null }
        val conquer = processWarNG(rng, attacker, getNextDefender, city, hooks)
        draft?.let { it.conquered = conquer; it.crewAfter = attacker.getCrew() }
        return Run(calls.toList(), draws.toList(), attacker.getCrew(), listOf(d1.getCrew(), d2.getCrew()), conquer, draft)
    }

    @Test
    fun `no plan — hook call order, draw stream and end state are identical to the unwrapped hooks (red probe)`() {
        val plain = run(null)
        // 같은 픽스처를 위임 훅으로 감싸도(계획 없음 = plannedStop null) 기록이 deep-equal
        val draws = mutableListOf<String>(); val calls = mutableListOf<String>()
        val rng = RecordingRng(seed, draws)
        val attacker = warGeneral(general(1, crew = 50000), attacker = true, rng = rng)
        val d1 = warGeneral(general(2, crew = 3000), attacker = false, rng = rng)
        val d2 = warGeneral(general(3, crew = 3000), attacker = false, rng = rng)
        val city = warCity(rng)
        val wrapped = object : opensamguk.logic.war.plan.DelegatingWarBattleHooks(RecordingHooks(calls)) {}
        val queue = ArrayDeque<WarUnit>(listOf(d1, d2))
        val conquer = processWarNG(rng, attacker, { _, reqNext -> if (reqNext) queue.removeFirstOrNull() else null }, city, wrapped)
        assertEquals(plain.calls, calls); assertEquals(plain.draws, draws)
        assertEquals(plain.attackerCrew, attacker.getCrew()); assertEquals(plain.defenderCrews, listOf(d1.getCrew(), d2.getCrew())); assertEquals(plain.conquer, conquer)
        // 오늘 동작의 형태 핀: 퇴각 없음, 수비자 격파 로그가 있고 페이즈 소진(또는 둘 다 격파)
        assertTrue(plain.calls.none { it.startsWith("retreat:") }, plain.calls.toString())
    }

    @Test
    fun `probe plan retreats after the first contact phase with the retreat cost, and differs from no plan`() {
        val plain = run(null)
        val probe = run(SealedBattlePlan(1, 10, "probe", null, null))
        assertNotEquals(plain.calls, probe.calls)
        val phases = probe.calls.filter { it.startsWith("phase:") }
        assertEquals(1, phases.size, probe.calls.toString())
        assertTrue(probe.calls.last { it.startsWith("retreat:") || it.startsWith("down:") }.startsWith("retreat:G2:false"), probe.calls.toString())
        // 퇴각 비용 = tryWound 쌍(nextBool(0.05) ×2) + result 로그 2개
        assertEquals(2, probe.draws.count { it == "nextBool(0.05)" })
        assertEquals(listOf("result:G1", "result:G2"), probe.calls.filter { it.startsWith("result:") })
        val draft = probe.draft!!
        assertEquals(PlanStop.PROBE, draft.stop); assertEquals(1, draft.stopAtPhase); assertTrue(draft.retreat)
        assertEquals("retreat", draft.result())
        assertEquals(1, draft.phases.size); assertEquals(2, draft.phases[0].defId); assertEquals("general", draft.phases[0].defKind); assertTrue(draft.phases[0].contact)
    }

    @Test
    fun `loss pct plan stops earlier than assault and two runs yield the same replay hash`() {
        val assault = run(SealedBattlePlan(1, 10, "assault", null, null))
        val loss = run(SealedBattlePlan(1, 10, "assault", 10, null))
        val names: (String, Int) -> String = { kind, id -> "$kind$id" }
        val a1 = run(SealedBattlePlan(1, 10, "assault", 10, null))
        val j1 = BattleReplayCodec.encodePhases(loss.draft!!, names); val j2 = BattleReplayCodec.encodePhases(a1.draft!!, names)
        assertEquals(j1, j2)
        assertEquals(BattleReplayCodec.replayHash(j1, mapOf("crewAfter" to loss.attackerCrew)), BattleReplayCodec.replayHash(j2, mapOf("crewAfter" to a1.attackerCrew)))
        // assault(조건 없음) = 오늘 동작과 같은 호출 기록(plannedStop 이 null 을 돌려준다)
        assertEquals(run(null).calls, assault.calls)
        // 손실 10% 조건은 병력 50,000 이 45,000 이하가 되는 페이즈에 멈춘다 — 조건이 참이 될 만큼 죽었다면 더 이른 퇴각
        if (loss.attackerCrew <= 45000) {
            assertTrue(loss.draft!!.retreat, loss.calls.toString()); assertEquals(PlanStop.LOSS_PCT, loss.draft.stop)
            assertTrue(loss.calls.size < assault.calls.size)
        } else {
            assertEquals(assault.calls, loss.calls, "조건이 참이 되지 않으면 돌격과 같다")
        }
    }

    @Test
    fun `M1 — a non-siege city as the defender still pays the retreat cost on a planned stop`() {
        val plain = run(null, cityFirst = true)
        val probe = run(SealedBattlePlan(1, 10, "probe", null, null), cityFirst = true)
        // 계획 없음: 비공성 성은 「한 대 맞고 재정비」 — 퇴각도, 격파 로그도 없이 페이즈가 이어진다
        assertTrue(plain.calls.none { it.startsWith("retreat:") }, plain.calls.toString())
        // probe: 첫 페이즈(상대 = 비공성 성) 뒤 계획 퇴각 — addLose/tryWound 비용 + retreat
        assertTrue(probe.calls.any { it == "retreat:C10:false" }, probe.calls.toString())
        // 퇴각 비용의 tryWound 쌍 — 성(`WarUnit.tryWound` 기본)은 draw 하지 않으므로 공격자 1회만 남는다
        assertEquals(1, probe.draws.count { it == "nextBool(0.05)" })
        assertEquals(listOf("result:G1", "result:C10"), probe.calls.filter { it.startsWith("result:") })
        assertEquals("retreat", probe.draft!!.result())
        assertEquals("city", probe.draft.phases.single().defKind)
    }
}
