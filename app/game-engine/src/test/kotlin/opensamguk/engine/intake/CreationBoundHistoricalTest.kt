package opensamguk.engine.intake

import opensamguk.common.wire.CreateGeneral

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreationBoundHistoricalTest {
    @Test fun `같은 이름 두 역사 ID의 접수는 ID별로 분리되고 같은 ID의 두 번째 접수는 거절된다`() {
        val hash = "a".repeat(64)
        val position = GeneralPositionSnapshot("fixture", hash, setOf("province"), emptySet())
            .withState(GeneralPositionState("fixture", hash, 10,
                StrategicNodeRef.LandProvince("province"), 1))
            .withState(GeneralPositionState("fixture", hash, 11,
                StrategicNodeRef.LandProvince("province"), 1))
        val person = TurnGeneral(id = 10, name = "동명이인", nationId = 0, cityId = 1, troopId = 0,
            stats = GeneralStats(60, 60, 60), experience = 0, dedication = 0,
            officerLevel = 0, npcState = 2, turnTime = Instant.EPOCH,
            meta = mapOf("npc_org" to "synthetic"))
        val world = InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 190, currentMonth = 1, tickSeconds = 3600,
                lastTurnTime = Instant.EPOCH, status = "OPEN", meta = mapOf("isunited" to 0),
                config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "mapName" to "han-world-v3")),
            worldId = WorldId(1), cities = listOf(City(1, "현", 0, level = 1)),
            generals = listOf(person, person.copy(id = 11)),
            generalPositionSnapshot = position, cityLandProvinceById = mapOf(1 to "province"),
        ))
        val handler = CreationHandler(world, ChangeRecorder())
        fun claim(id: Int, account: Int) = handler.handle(CreateGeneral(
            accountId = account, worldId = 1, clientRequestId = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6",
            choiceKind = "HISTORICAL", historicalGeneralId = id))

        assertTrue(claim(10, 7).ok)
        val secondClaim = claim(10, 8)
        assertFalse(secondClaim.ok)
        assertEquals("HISTORICAL_PERSON_UNAVAILABLE", secondClaim.errorCode)
        assertTrue(claim(11, 8).ok)
        assertEquals("7", world.getGeneralById(10)?.userId)
        assertEquals("8", world.getGeneralById(11)?.userId)
    }

    @Test fun `묶인 역사 인물도 사람에게 넘어오고 기존 부 관계는 남는다`() {
        val hash = "a".repeat(64)
        val position = GeneralPositionSnapshot("fixture", hash, setOf("province"), emptySet())
            .withState(GeneralPositionState("fixture", hash, 10,
                StrategicNodeRef.LandProvince("province"), 1))
        val bound = Retainer(1, 20, "EXISTING", 10, "묶인 인물", "retainer")
        val person = TurnGeneral(id = 10, name = "묶인 인물", nationId = 0, cityId = 1, troopId = 0,
            stats = GeneralStats(60, 60, 60), experience = 0, dedication = 0,
            officerLevel = 0, npcState = 2, turnTime = Instant.EPOCH,
            meta = mapOf("npc_org" to "historical"))
        val world = InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 190, currentMonth = 1, tickSeconds = 3600,
                lastTurnTime = Instant.EPOCH, status = "OPEN", meta = mapOf("isunited" to 0),
                config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "mapName" to "han-world-v3")),
            worldId = WorldId(1), cities = listOf(City(1, "현", 0, level = 1)),
            generals = listOf(person), retainers = listOf(bound),
            generalPositionSnapshot = position, cityLandProvinceById = mapOf(1 to "province"),
        ))

        val result = CreationHandler(world, ChangeRecorder()).handle(CreateGeneral(
            accountId = 7, worldId = 1, clientRequestId = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6",
            choiceKind = "HISTORICAL", historicalGeneralId = 10))

        assertTrue(result.ok, "errorCode=${result.errorCode}")
        assertEquals("7", world.getGeneralById(10)?.userId)
        assertEquals(1, world.getGeneralById(10)?.npcState)
        assertEquals(bound, world.getRetainerById(1))
    }
}
