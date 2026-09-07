package opensamguk.engine.operation

import opensamguk.common.wire.OperationActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.intake.OperationHandler
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.operation.OperationRules
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4X-B 「관리자 개입 0」 시뮬(계획 §4X-B 게이트, 로드맵 4단계 관문) — 이동을 `applyGeneralDirtyFree` 로 대신하던
 * `OperationIntakeTest` 정산 케이스와 달리 **실제 예약 명령 경로**를 탄다: 선언(인테이크) → 참여(인테이크) →
 * `ReservedTurnHandler.handle("che_출병")`(전투·점령·국가 소멸 캐스케이드 전부 실경로) → `OperationMonthlyService.settle`
 * → 「달성」. 세계 상태를 직접 만지는 호출은 없다. 픽스처는 [opensamguk.engine.turn.ReservedTurnWarDrainTest] 의
 * 점령 케이스(수비 장수 1명·병력 1·성벽 1)를 그대로 쓴다.
 */
class OperationAdminZeroSimTest {
    private val t0 = Instant.parse("0200-01-01T12:00:00Z")
    private val hiddenSeed = "00000000000000000000000000000000"
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `declare, join, reserved sortie conquers the target, monthly settle achieves — no direct world writes`() {
        val world = warWorld()
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear = 184)
        val recorder = handler.recorder
        val ops = OperationHandler(world, recorder)

        // 1) 선언(수뇌부 = officer_level 12) + 참여 — 인테이크 채널만.
        val declared = ops.handleDeclare(
            TurnDaemonCommand.OperationDeclare(generalId = 100, kind = OperationRules.KIND_CAPTURE_CITY, targetCityId = 31, title = "c31 공략", deadlineMonths = 3),
        ) as OperationActionResult
        assertTrue(declared.ok, declared.reason)
        val joined = ops.handleJoin(TurnDaemonCommand.OperationJoin(generalId = 100, operationId = declared.id, role = "main")) as OperationActionResult
        assertTrue(joined.ok, joined.reason)
        val opId = declared.id!!
        assertEquals(OperationRules.STATUS_ACTIVE, world.getOperationById(opId)!!.status)
        assertEquals(7, world.getOperationUnitById(joined.id!!)!!.joinedCityId)

        // 2) 예약 명령 실경로: che_출병 → 전투 → 점령(ConquerCity) → 수비국 멸망 캐스케이드.
        val handled = handler.handle(100, ReservedTurn("che_출병", """{"destCityID":31}"""), year = 200, month = 1, date = "12:00")
        assertFalse(handled.fellBack)
        assertEquals("che_출병", handled.definition.key)
        assertEquals(1, world.getCityById(31)!!.nationId, "목표 도시가 점령돼야 한다")
        assertEquals(31, world.getGeneralById(100)!!.cityId, "공격 장수가 목표 도시로 이동해야 한다")
        assertTrue(2 in recorder.deletedNationIds(), "수비국이 멸망해야 한다")
        // 수비국 소멸 프룬은 수비국 작전만 건드린다 — 공격국 작전은 그대로.
        assertEquals(OperationRules.STATUS_ACTIVE, world.getOperationById(opId)!!.status)

        // 3) 월 정산 — 이정표(출발·도달·목표)가 세계 상태에서 재계산되고 「달성」 으로 전이한다.
        OperationMonthlyService().settle(world, recorder)
        val op = world.getOperationById(opId)!!
        assertEquals(OperationRules.STATUS_ACHIEVED, op.status)
        assertEquals(OperationRules.CLOSED_ACHIEVED, op.closedReason)
        assertTrue(op.milestones.departed && op.milestones.arrived && op.milestones.objective, "$op")

        // 4) flush 산출물: 작전 생성 1 + 달성 갱신은 같은 틱이라 created 로 실린다, 국가 기록 2줄(선언·달성), 수비국 캐스케이드.
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(1, payload.createdOperations.size)
        assertEquals(OperationRules.STATUS_ACHIEVED, payload.createdOperations.single().status)
        assertTrue(payload.deletedNations.contains(2))
        val nationHistory = payload.logEntries.filter { it.scope == "NATION" && it.category == "HISTORY" && it.nationId == 1 }.map { it.text }
        assertTrue(nationHistory.any { it.contains("작전을 선언했습니다") }, "$nationHistory")
        assertTrue(nationHistory.any { it.contains("작전 목표를 달성했습니다") }, "$nationHistory")
    }

    private fun warWorld(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0,
                config = linkedMapOf("mapName" to "che"),
            ),
            generals = listOf(
                general(id = 100, nationId = 1, cityId = 7, crew = 50_000, leadership = 100, strength = 100, intelligence = 90, officerLevel = 12),
                general(id = 201, nationId = 2, cityId = 31, crew = 1, leadership = 20, strength = 20, intelligence = 20, officerLevel = 12),
            ),
            cities = listOf(
                city(id = 7, nationId = 1),
                city(id = 31, nationId = 2, level = 1, defence = 1, wall = 1, defenceMax = 10, wallMax = 10),
            ),
            nations = listOf(nation(id = 1, capital = 7), nation(id = 2, capital = 31, rice = 10_000)),
            diplomacy = listOf(
                TurnDiplomacy(fromNationId = 1, toNationId = 2, state = 0, term = 0),
                TurnDiplomacy(fromNationId = 2, toNationId = 1, state = 0, term = 0),
            ),
            worldId = opensamguk.common.world.WorldId(1),
        ),
    )

    private fun general(id: Int, nationId: Int, cityId: Int, crew: Int, leadership: Int, strength: Int, intelligence: Int, officerLevel: Int): TurnGeneral = TurnGeneral(
        id = id, name = "g$id", nationId = nationId, cityId = cityId, troopId = 0,
        stats = GeneralStats(leadership = leadership, strength = strength, intelligence = intelligence),
        experience = 0, dedication = 0, officerLevel = officerLevel, gold = 100_000, rice = 100_000,
        crew = crew, crewTypeId = 1100, train = 100, atmos = 100, turnTime = t0, meta = linkedMapOf("killturn" to 1000),
    )

    private fun city(id: Int, nationId: Int, level: Int = 8, defence: Int = 500, wall: Int = 500, defenceMax: Int = 500, wallMax: Int = 500): City = City(
        id = id, name = "c$id", nationId = nationId, level = level,
        population = 100_000, populationMax = 100_000, agriculture = 20_000, agricultureMax = 20_000,
        commerce = 20_000, commerceMax = 20_000, security = 20_000, securityMax = 20_000,
        supplyState = 1, frontState = 3, defence = defence, defenceMax = defenceMax, wall = wall, wallMax = wallMax,
        conflict = "{}", meta = linkedMapOf("trust" to 80),
    )

    private fun nation(id: Int, capital: Int, rice: Int = 100_000): Nation =
        Nation(id = id, name = "n$id", color = "#000", capitalCityId = capital, gold = 100_000, rice = rice, tech = 0.0, level = 2)
}
