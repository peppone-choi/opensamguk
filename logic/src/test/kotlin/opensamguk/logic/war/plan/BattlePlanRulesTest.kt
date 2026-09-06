package opensamguk.logic.war.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec v4.1 §8 — 게이트 순서 표(3 명령)·입력 범위·`plannedStop` 판정 표·`result` 표 4+1행·codec 결정성·키 정렬. */
class BattlePlanRulesTest {

    private fun plan(stance: String = "assault", pct: Int? = null, morale: Int? = null) = SealedBattlePlan(1, 31, stance, pct, morale)

    @Test
    fun `save input range gates`() {
        assertEquals(BattlePlanRules.SaveInput.Denied(BattlePlanRules.REASON_INPUT), BattlePlanRules.saveInput(null, "assault", null, null))
        assertEquals(BattlePlanRules.SaveInput.Denied(BattlePlanRules.REASON_INPUT), BattlePlanRules.saveInput(31, "advance", null, null))
        assertEquals(BattlePlanRules.SaveInput.Denied(BattlePlanRules.REASON_INPUT), BattlePlanRules.saveInput(31, "assault", 9, null))
        assertEquals(BattlePlanRules.SaveInput.Denied(BattlePlanRules.REASON_INPUT), BattlePlanRules.saveInput(31, "assault", 91, null))
        assertEquals(BattlePlanRules.SaveInput.Denied(BattlePlanRules.REASON_INPUT), BattlePlanRules.saveInput(31, "assault", null, 101))
        assertEquals(BattlePlanRules.SaveInput.Ok(31, "probe", 10, 0), BattlePlanRules.saveInput(31, "probe", 10, 0))
        assertEquals(BattlePlanRules.SaveInput.Ok(31, "assault", null, null), BattlePlanRules.saveInput(31, "assault", null, null))
    }

    @Test
    fun `state gate order — save, seal, delete`() {
        assertEquals(BattlePlanRules.REASON_NO_TARGET, BattlePlanRules.saveDeny(targetExists = false, targetNationId = null, myNationId = 1, existingSealed = true))
        assertEquals(BattlePlanRules.REASON_OWN_CITY, BattlePlanRules.saveDeny(true, 1, 1, existingSealed = true))
        assertEquals(BattlePlanRules.REASON_SEALED, BattlePlanRules.saveDeny(true, 2, 1, existingSealed = true))
        assertNull(BattlePlanRules.saveDeny(true, 0, 1, existingSealed = false), "공백지도 목표가 된다")
        assertNull(BattlePlanRules.saveDeny(true, 0, 0, existingSealed = false), "재야(국가 0)는 아군 도시 판정을 받지 않는다")
        assertEquals(BattlePlanRules.REASON_NO_PLAN, BattlePlanRules.sealDeny(planMineUnresolved = false, sealed = true))
        assertEquals(BattlePlanRules.REASON_SEALED, BattlePlanRules.sealDeny(true, sealed = true))
        assertNull(BattlePlanRules.sealDeny(true, sealed = false))
        assertEquals(BattlePlanRules.REASON_SEALED, BattlePlanRules.deleteDeny(true, sealed = true))
    }

    @Test
    fun `plannedStop table — probe, loss pct, morale, simultaneous order, none`() {
        assertEquals(PlanStop.PROBE, BattlePlanRules.plannedStop(plan("probe"), 1, 1000, 1000, 100.0))
        assertNull(BattlePlanRules.plannedStop(plan("assault"), 1, 1000, 1000, 100.0))
        // 손실 30% — 700 이하가 되면 정지, 701 은 아님
        assertEquals(PlanStop.LOSS_PCT, BattlePlanRules.plannedStop(plan(pct = 30), 2, 1000, 700, 100.0))
        assertNull(BattlePlanRules.plannedStop(plan(pct = 30), 2, 1000, 701, 100.0))
        // 사기 40 미만
        assertEquals(PlanStop.MORALE, BattlePlanRules.plannedStop(plan(morale = 40), 2, 1000, 1000, 39.9))
        assertNull(BattlePlanRules.plannedStop(plan(morale = 40), 2, 1000, 1000, 40.0))
        // 동시 → probe > loss > morale
        assertEquals(PlanStop.PROBE, BattlePlanRules.plannedStop(plan("probe", 30, 40), 1, 1000, 100, 0.0))
        assertEquals(PlanStop.LOSS_PCT, BattlePlanRules.plannedStop(plan("assault", 30, 40), 1, 1000, 100, 0.0))
    }

    @Test
    fun `result table — 4 rows plus A-beaten-then-B-survives-exhausted is repelled`() {
        assertEquals("conquered", BattlePlanRules.resultOf(conquered = true, retreat = true, lastDefenderDown = true))
        assertEquals("retreat", BattlePlanRules.resultOf(conquered = false, retreat = true, lastDefenderDown = true))
        assertEquals("defenders_down", BattlePlanRules.resultOf(conquered = false, retreat = false, lastDefenderDown = true))
        assertEquals("repelled", BattlePlanRules.resultOf(conquered = false, retreat = false, lastDefenderDown = false))
        // A 격파(onDefenderDownLog) 뒤 B 와 싸우다 페이즈 소진 — B 의 onPhaseLog 가 플래그를 리셋한다 → repelled
        val draft = BattleReplayDraft(1, 1, 31, 2, "seed", plan(), 1000, 100, "in")
        draft.lastDefenderDown = true; draft.lastDefenderDown = false
        assertEquals("repelled", draft.result())
        assertEquals(BattlePlanRules.RESULT_LABELS.keys, setOf("retreat", "repelled", "defenders_down", "conquered"))
    }

    @Test
    fun `codec is deterministic, key-sorted, and name-injected`() {
        fun draft(): BattleReplayDraft = BattleReplayDraft(1, 1, 31, 2, "seed", plan(pct = 30), 1000, 100, "in").apply {
            phases += ReplayPhase(1, 201, "general", true, 100, 50, 900, 150)
            phases += ReplayPhase(2, 31, "city", true, 80, 40, 820, 400)
            stop = PlanStop.LOSS_PCT; stopAtPhase = 2
        }
        val names: (String, Int) -> String = { kind, id -> if (kind == "general") "화웅$id" else "호뢰관$id" }
        val a = BattleReplayCodec.encodePhases(draft(), names)
        val b = BattleReplayCodec.encodePhases(draft(), names)
        assertEquals(a, b)
        assertTrue(a.startsWith("{\"phases\":[{\"contact\":true,\"crewA\":900,\"deadA\":100,\"deadD\":50,\"def\":\"화웅201\",\"defId\":201,\"defKind\":\"general\",\"hpD\":150,\"i\":1}"), a)
        assertTrue(a.endsWith("\"stop\":{\"atPhase\":2,\"kind\":\"loss_pct\"},\"v\":1}"), a)
        val h1 = BattleReplayCodec.replayHash(a, mapOf("crewAfter" to 820, "dead" to 180))
        assertEquals(h1, BattleReplayCodec.replayHash(b, mapOf("dead" to 180, "crewAfter" to 820)))
        assertEquals(64, h1.length)
        // 다른 계획(pct 10 vs 90) → input_hash 다름, Double 은 toBits
        val i1 = BattleReplayCodec.inputHash(mapOf("plan" to mapOf("pct" to 10), "train" to 0.1))
        val i2 = BattleReplayCodec.inputHash(mapOf("plan" to mapOf("pct" to 90), "train" to 0.1))
        assertTrue(i1 != i2)
        assertEquals("{\"a\":4591870180066957722,\"b\":[1,\"x\"],\"c\":null}", BattleReplayCodec.encode(mapOf("c" to null, "b" to listOf(1, "x"), "a" to 0.1)))
    }
}
