package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.plan.BattleReplayCodec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4X-C spec v4.1 §8 — `ReservedTurnHandler` 출병 후처리: 봉인 계획이 있으면 `battle_replay` INSERT(이름·해시·정산 채움) +
 * 계획 소비 + 개인 기록 1줄, 없으면 채널 0. 픽스처는 [ReservedTurnWarDrainTest] 와 같다. 같은 계획으로 두 번 = 같은 replay_hash.
 */
class ReservedTurnBattlePlanTest {
    private val t0 = Instant.parse("0200-01-01T12:00:00Z")
    private val hiddenSeed = "00000000000000000000000000000000"
    private val registry = CommandRegistry(GeneralActionPipeline())

    private fun run(plan: BattlePlan?, conquer: Boolean = false): Triple<ReservedTurnHandler, InMemoryTurnWorld, List<BattleReplayInsert>> {
        val world = if (conquer) {
            warWorld(defenders = listOf(defender(201, crew = 1).copy(officerLevel = 12)), defenderCity = city(31, 2, level = 1, defence = 1, wall = 1, defenceMax = 10, wallMax = 10), defenderNation = nation(2, 31, rice = 10_000), plans = listOfNotNull(plan))
        } else {
            warWorld(defenders = listOf(defender(201), defender(202)), plans = listOfNotNull(plan))
        }
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear = 184)
        val handled = handler.handle(100, ReservedTurn("che_출병", """{"destCityID":31}"""), year = 200, month = 1, date = "12:00")
        assertFalse(handled.fellBack)
        return Triple(handler, world, handler.recorder.battleReplayInserts())
    }

    private fun sealedPlan(stance: String = "probe", pct: Int? = null) =
        BattlePlan(id = 5, generalId = 100, targetCityId = 31, stance = stance, retreatLossPct = pct, sealedAt = t0, sealedYear = 200, sealedMonth = 1, sealedPhase = 1)

    @Test
    fun `no plan — no replay row, no plan update, no replay log`() {
        val (handler, world, rows) = run(null)
        assertTrue(rows.isEmpty())
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.battlePlans.isEmpty() && dirty.createdBattlePlans.isEmpty())
        assertTrue(dirty.logs.none { it.text.contains("리플레이") })
        assertTrue(handler.recorder.dirtyGeneralIds().contains(100))
    }

    @Test
    fun `probe plan — replay row with names, hashes, settlement, consumed plan and the personal log`() {
        val (_, world, rows) = run(sealedPlan("probe"))
        val row = rows.single().columns
        assertEquals(1, row["id"]); assertEquals(5, row["battle_plan_id"]); assertNull(row["operation_id"])
        assertEquals(100, row["attacker_general_id"]); assertEquals("g100", row["attacker_name"]); assertEquals(1, row["attacker_nation_id"])
        assertEquals(31, row["defender_city_id"]); assertEquals("c31", row["defender_city_name"]); assertEquals(2, row["defender_nation_id"])
        assertEquals(listOf(200, 1, 1), listOf(row["year"], row["month"], row["phase"]))
        assertEquals(32, (row["war_seed"] as String).length); assertEquals(64, (row["input_hash"] as String).length); assertEquals(64, (row["replay_hash"] as String).length)
        val json = row["battle_phases_json"] as String
        assertTrue(json.startsWith("{\"phases\":[{\"contact\":true,\"crewA\":"), json)
        assertTrue(json.contains("\"def\":\"g201\",\"defId\":201,\"defKind\":\"general\""), json)
        assertTrue(json.endsWith("\"stop\":{\"atPhase\":1,\"kind\":\"probe\"},\"v\":1}"), json)
        assertEquals("probe", row["plan_stop"]); assertEquals("probe", row["plan_stance"])
        assertTrue(row["result"] in setOf("retreat", "defenders_down"), "탐색은 첫 접촉 페이즈 뒤 멈춘다: ${row["result"]}")
        assertEquals(50_000, row["attacker_crew_before"]); assertTrue((row["attacker_crew_after"] as Int) <= 50_000)
        assertEquals(1, world.getBattlePlanById(5)!!.resolvedMonth, "계획은 소비된다(F7)")
        val dirty = world.consumeDirtyState()
        assertEquals(5, dirty.battlePlans.single().id)
        assertTrue(dirty.logs.any { it.scope == "general" && it.generalId == 100 && it.text.contains("리플레이 <Y>#1</> 가 기록되었습니다.") })
    }

    @Test
    fun `assault plan with loss pct on a conquest — result conquered, defender nation cascade keeps the row`() {
        val (handler, world, rows) = run(sealedPlan("assault", pct = 10), conquer = true)
        val row = rows.single().columns
        assertEquals("conquered", row["result"]); assertNull(row["plan_stop"])
        assertEquals(1, world.getCityById(31)!!.nationId)
        assertTrue(2 in handler.recorder.deletedNationIds())
        assertEquals(2, row["defender_nation_id"], "수비국 id 는 FK 없는 스냅샷 — 멸망해도 남는다")
    }

    @Test
    fun `same plan twice yields the same replay hash and phases json`() {
        val (_, _, a) = run(sealedPlan("assault", pct = 10))
        val (_, _, b) = run(sealedPlan("assault", pct = 10))
        assertEquals(a.single().columns["battle_phases_json"], b.single().columns["battle_phases_json"])
        assertEquals(a.single().columns["replay_hash"], b.single().columns["replay_hash"])
        assertEquals(a.single().columns["input_hash"], b.single().columns["input_hash"])
        assertNotNull(BattleReplayCodec.sha256Hex("x"))
    }

    private fun warWorld(defenders: List<TurnGeneral>, defenderCity: City = city(31, 2, level = 5, defence = 500_000, wall = 500_000, defenceMax = 500_000, wallMax = 500_000), defenderNation: Nation = nation(2, 31, rice = 100_000), plans: List<BattlePlan>): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, currentPhase = 1, tickSeconds = 3600, lastTurnTime = t0, config = linkedMapOf("mapName" to "che")),
            generals = listOf(attacker()) + defenders,
            cities = listOf(city(7, 1), defenderCity),
            nations = listOf(nation(1, 7), defenderNation),
            diplomacy = listOf(TurnDiplomacy(1, 2, 0, 0), TurnDiplomacy(2, 1, 0, 0)),
            battlePlans = plans,
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun attacker(): TurnGeneral = general(100, 1, 7, crew = 50_000, leadership = 100, strength = 100, intelligence = 90)
    private fun defender(id: Int, crew: Int = 200): TurnGeneral = general(id, 2, 31, crew = crew, leadership = 20, strength = 20, intelligence = 20)

    private fun general(id: Int, nationId: Int, cityId: Int, crew: Int, leadership: Int, strength: Int, intelligence: Int): TurnGeneral = TurnGeneral(
        id = id, name = "g$id", nationId = nationId, cityId = cityId, troopId = 0, stats = GeneralStats(leadership, strength, intelligence),
        experience = 0, dedication = 0, officerLevel = 1, gold = 100_000, rice = 100_000, crew = crew, crewTypeId = 1100, train = 100, atmos = 100,
        turnTime = t0, meta = linkedMapOf("killturn" to 1000),
    )

    private fun city(id: Int, nationId: Int, level: Int = 8, defence: Int = 500, wall: Int = 500, defenceMax: Int = 500, wallMax: Int = 500): City = City(
        id = id, name = "c$id", nationId = nationId, level = level, population = 100_000, populationMax = 100_000, agriculture = 20_000, agricultureMax = 20_000,
        commerce = 20_000, commerceMax = 20_000, security = 20_000, securityMax = 20_000, supplyState = 1, frontState = 3,
        defence = defence, defenceMax = defenceMax, wall = wall, wallMax = wallMax, conflict = "{}", meta = linkedMapOf("trust" to 80),
    )

    private fun nation(id: Int, capital: Int, rice: Int = 100_000): Nation =
        Nation(id = id, name = "n$id", color = "#000", capitalCityId = capital, gold = 100_000, rice = rice, tech = 0.0, level = 2)
}
