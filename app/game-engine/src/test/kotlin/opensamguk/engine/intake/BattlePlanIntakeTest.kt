package opensamguk.engine.intake

import opensamguk.common.wire.BattlePlanActionResult
import opensamguk.common.wire.OperationActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.engine.war.BattleCommandContextBuilder
import opensamguk.logic.war.plan.BattlePlanRules
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * spec v4.1 §8 `BattlePlanIntakeTest` — 3 명령 채널·봉인 뒤 거부·소비 뒤 재저장 허용(F7)·툼스톤(장수 삭제 → 계획 프룬 + pending
 * 리플레이 id NULL, F3 / 국가 소멸 → operation_id NULL, N4)·같은 틱 저장→봉인·`BattleCommandContextBuilder.sealedPlans`
 * (같은 순 봉인 적용 F5, 미봉인·미래 봉인 제외, autorunMode 빈 맵 M5).
 */
class BattlePlanIntakeTest {

    private val t0 = Instant.parse("0200-03-11T00:00:00Z")

    private fun general(id: Int, nationId: Int = 1, cityId: Int = 7, officerLevel: Int = 5) = TurnGeneral(
        id = id, name = "장수$id", nationId = nationId, cityId = cityId, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0, officerLevel = officerLevel,
        gold = 1000, rice = 1000, crew = 5000, crewTypeId = 1100, atmos = 50, turnTime = t0, role = GeneralRole(),
    )

    private fun city(id: Int, nationId: Int) = City(id = id, name = "도시$id", nationId = nationId, level = 5, frontState = 0, supplyState = 1)

    private fun world(vararg generals: TurnGeneral, phase: Int = 2): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, currentPhase = phase, tickSeconds = 3600, lastTurnTime = t0, config = linkedMapOf("mapName" to "che"), meta = mapOf("startYear" to 184)),
            generals = generals.toList().ifEmpty { listOf(general(10)) },
            cities = listOf(city(7, 1), city(31, 2), city(3, 0)),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000, capitalCityId = 7), Nation(id = 2, name = "위", color = "#00f", gold = 1000, capitalCityId = 31)),
            diplomacy = listOf(TurnDiplomacy(1, 2, 0, 0), TurnDiplomacy(2, 1, 0, 0)),
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    private fun save(h: BattlePlanHandler, generalId: Int = 10, city: Int = 31, stance: String = "assault", pct: Int? = null, morale: Int? = null) =
        h.handleSave(TurnDaemonCommand.BattlePlanSave(generalId = generalId, targetCityId = city, stance = stance, retreatLossPct = pct, retreatMoraleBelow = morale)) as BattlePlanActionResult

    @Test
    fun `save gates, draft update bumps version, seal locks, delete only drafts`() {
        val world = world(); val recorder = ChangeRecorder(); val h = BattlePlanHandler(world, recorder)
        assertEquals("장수가 존재하지 않습니다.", save(h, generalId = 99).reason)
        assertEquals(BattlePlanRules.REASON_INPUT, save(h, stance = "advance").reason)
        assertEquals(BattlePlanRules.REASON_INPUT, save(h, pct = 5).reason)
        assertEquals(BattlePlanRules.REASON_NO_TARGET, save(h, city = 999).reason)
        assertEquals(BattlePlanRules.REASON_OWN_CITY, save(h, city = 7).reason)
        val v1 = save(h, pct = 30); assertTrue(v1.ok); assertNotNull(v1.id)
        assertEquals(1, world.getBattlePlanById(v1.id!!)!!.version)
        val v2 = save(h, stance = "probe", pct = 40)
        assertEquals(v1.id, v2.id); assertEquals(2, world.getBattlePlanById(v1.id!!)!!.version); assertEquals("probe", world.getBattlePlanById(v1.id!!)!!.stance)
        // 공백지도 목표가 된다 — 다른 도시는 다른 계획
        val neutral = save(h, city = 3); assertTrue(neutral.ok); assertTrue(neutral.id != v1.id)
        assertEquals(BattlePlanRules.REASON_NO_PLAN, (h.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = 999)) as BattlePlanActionResult).reason)
        assertEquals(BattlePlanRules.REASON_INPUT, (h.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = null)) as BattlePlanActionResult).reason)
        val seal = h.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = v1.id)) as BattlePlanActionResult
        assertTrue(seal.ok)
        val sealed = world.getBattlePlanById(v1.id!!)!!
        assertTrue(sealed.sealed); assertEquals(listOf(200, 3, 2), listOf(sealed.sealedYear, sealed.sealedMonth, sealed.sealedPhase))
        assertEquals(BattlePlanRules.REASON_SEALED, (h.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = v1.id)) as BattlePlanActionResult).reason)
        assertEquals(BattlePlanRules.REASON_SEALED, save(h, pct = 50).reason, "봉인 뒤 수정은 인테이크 거부 사유")
        assertEquals(BattlePlanRules.REASON_SEALED, (h.handleDelete(TurnDaemonCommand.BattlePlanDelete(generalId = 10, planId = v1.id)) as BattlePlanActionResult).reason)
        assertTrue((h.handleDelete(TurnDaemonCommand.BattlePlanDelete(generalId = 10, planId = neutral.id)) as BattlePlanActionResult).ok)
        assertNull(world.getBattlePlanById(neutral.id!!))
        // 남의 계획은 「계획이 없습니다.」
        val other = world(general(10), general(11, officerLevel = 1)); val h2 = BattlePlanHandler(other, ChangeRecorder())
        val mine = save(h2)
        assertEquals(BattlePlanRules.REASON_NO_PLAN, (h2.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 11, planId = mine.id)) as BattlePlanActionResult).reason)

        val payload = flush(world, recorder)
        // 같은 틱 저장→갱신→봉인 = created 1행(신규 행이므로 UPDATE 에 실리지 않는다), 삭제된 초안은 same-tick 이라 DELETE 없음
        assertEquals(1, payload.createdBattlePlans.size); assertTrue(payload.updatedBattlePlans.isEmpty()); assertTrue(payload.deletedBattlePlanIds.isEmpty())
        assertEquals("probe", payload.createdBattlePlans.single().stance); assertNotNull(payload.createdBattlePlans.single().sealedAt)
        assertEquals(2, payload.worldStateUpdate["max_battle_plan_id"])
        assertTrue(payload.logEntries.any { it.scope == "GENERAL" && it.text.contains("출병 계획을 봉인했습니다") })
        assertTrue(payload.battleReplayInserts.isEmpty())
    }

    @Test
    fun `consumed plan frees the key and is left out of sealedPlans, future or unsealed plans are excluded, autorun empties the map`() {
        val world = world(); val recorder = ChangeRecorder(); val h = BattlePlanHandler(world, recorder)
        val first = save(h, pct = 30)
        h.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = first.id))
        val ctx = { autorun: Boolean -> BattleCommandContextBuilder.build(world, 10, 31, "0".repeat(32), 200, 3, autorunMode = autorun) }
        assertEquals(setOf(31), ctx(false).sealedPlans.keys, "같은 순 봉인도 적용된다(F5)")
        assertEquals(30, ctx(false).sealedPlans.getValue(31).retreatLossPct)
        assertTrue(ctx(true).sealedPlans.isEmpty(), "AI 가 명령을 바꾼 턴은 빈 맵(M5)")
        // 소비 → 키가 풀린다, sealedPlans 에서도 빠진다
        val plan = world.getBattlePlanById(first.id!!)!!
        world.updateBattlePlan(plan.copy(resolvedYear = 200, resolvedMonth = 3, resolvedPhase = 2))
        assertTrue(ctx(false).sealedPlans.isEmpty())
        val again = save(h, pct = 60); assertTrue(again.ok); assertTrue(again.id != first.id)
        assertEquals(1, world.getBattlePlanById(again.id!!)!!.version)
        // 미봉인 초안은 실리지 않는다; 미래 순 봉인(다음 순)도 실리지 않는다
        assertTrue(ctx(false).sealedPlans.isEmpty())
        world.updateBattlePlan(world.getBattlePlanById(again.id!!)!!.copy(sealedAt = t0, sealedYear = 200, sealedMonth = 3, sealedPhase = 3))
        assertTrue(ctx(false).sealedPlans.isEmpty(), "sealedDate > executingDate 는 제외")
        world.updateBattlePlan(world.getBattlePlanById(again.id!!)!!.copy(sealedYear = 200, sealedMonth = 2, sealedPhase = 3))
        assertEquals(setOf(31), ctx(false).sealedPlans.keys)
    }

    @Test
    fun `tombstones — general death nulls pending replay ids and prunes plans, nation removal nulls operation_id`() {
        val world = world(general(10), general(11, officerLevel = 5)); val recorder = ChangeRecorder(); val h = BattlePlanHandler(world, recorder)
        val plan = save(h, pct = 30); h.handleSeal(TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = plan.id))
        world.consumeDirtyState() // 지난 틱에 flush 됐다고 치자
        val op = OperationHandler(world, recorder).handleDeclare(TurnDaemonCommand.OperationDeclare(generalId = 11, kind = "capture_city", targetCityId = 31, title = "위 공략", deadlineMonths = 2)) as OperationActionResult
        val replayId = recorder.recordBattleReplayInsert(linkedMapOf("battle_plan_id" to plan.id, "operation_id" to op.id, "attacker_general_id" to 10, "attacker_name" to "장수10"))
        assertEquals(1, replayId)
        val otherId = recorder.recordBattleReplayInsert(linkedMapOf("battle_plan_id" to null, "operation_id" to null, "attacker_general_id" to 11, "attacker_name" to "장수11"))
        assertEquals(2, otherId)
        // F3: 같은 틱 사망 → attacker_general_id·battle_plan_id NULL, 계획은 프룬(DB CASCADE, DELETE 기록 없음)
        assertTrue(recorder.markGeneralDeleted(world, 10))
        val rows = recorder.battleReplayInserts()
        assertNull(rows[0].columns["attacker_general_id"]); assertNull(rows[0].columns["battle_plan_id"]); assertEquals(op.id, rows[0].columns["operation_id"])
        assertEquals(11, rows[1].columns["attacker_general_id"])
        assertNull(world.getBattlePlanById(plan.id!!))
        // N4: 같은 틱 국가 소멸 → 그 국가 작전에 연결된 pending 리플레이의 operation_id NULL
        assertTrue(recorder.markNationDeleted(world, 1))
        assertNull(recorder.battleReplayInserts()[0].columns["operation_id"])
        val payload = flush(world, recorder)
        assertEquals(2, payload.battleReplayInserts.size); assertTrue(payload.deletedBattlePlanIds.isEmpty()); assertTrue(payload.updatedBattlePlans.isEmpty())
        assertEquals(1, payload.battleReplayInserts[0].columns["id"])
    }
}
