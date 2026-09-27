package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.ActiveWorldMapValidator
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

/** 위치 권위 spec §2.1·§2.3-1: isGeneralAtCity 는 프로필별 정의이고, HWIHA 는 전 장수 위치 행을 요구한다. */
class GeneralAtCityTest {
    private val hash = "b".repeat(64)
    private val p1 = StrategicNodeRef.LandProvince("p1")
    private val p2 = StrategicNodeRef.LandProvince("p2")
    private fun general(id: Int, city: Int) = TurnGeneral(id = id, name = "G$id", nationId = 0, cityId = city,
        troopId = 0, stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0,
        officerLevel = 0, turnTime = Instant.EPOCH)
    private fun positions(vararg rows: Pair<Int, StrategicNodeRef>): GeneralPositionSnapshot =
        rows.fold(GeneralPositionSnapshot("r1", hash, setOf("p1", "p2"), emptySet())) { acc, (id, node) ->
            acc.withState(GeneralPositionState("r1", hash, id, node, 1))
        }
    private fun snapshot(profile: String?, generals: List<TurnGeneral>, positions: GeneralPositionSnapshot?) = WorldSnapshot(
        TurnWorldState(1, 200, 1, 60, Instant.EPOCH,
            config = mapOf("mapName" to "han-world-v3") + (profile?.let { mapOf("ruleProfile" to it) } ?: emptyMap())),
        worldId = WorldId(1), generals = generals, generalPositionSnapshot = positions,
        cityLandProvinceById = mapOf(10 to "p1", 20 to "p2"),
    )

    @Test
    fun `sammo keeps the battlefield definition and needs no rows`() {
        val world = InMemoryTurnWorld(snapshot("SAMMO", listOf(general(7, 10)), null))
        assertTrue(world.isGeneralAtCity(7))
        assertEquals(!world.isGeneralAtBattlefield(7), world.isGeneralAtCity(7))
    }

    @Test
    fun `hwiha compares the reference city's province with the position`() {
        val world = InMemoryTurnWorld(snapshot("HWIHA", listOf(general(7, 10), general(8, 10)), positions(7 to p1, 8 to p2)))
        assertTrue(world.isGeneralAtCity(7))   // 위치 p1 == 城 10 의 省 p1
        assertFalse(world.isGeneralAtCity(8))  // 위치 p2, 기준 城 10 은 p1 — 城에 없음
        assertEquals(p2, world.positionOf(8))
    }

    @Test
    fun `hwiha boot fails when a living general has no position row`() {
        assertFailsWith<IllegalArgumentException> {
            ActiveWorldMapValidator.validate(snapshot("HWIHA", listOf(general(7, 10), general(8, 20)), positions(7 to p1)))
        }
    }

    @Test
    fun `hwiha boot fails when a reference city has no province binding`() {
        assertFailsWith<IllegalArgumentException> {
            ActiveWorldMapValidator.validate(snapshot("HWIHA", listOf(general(7, 99)), positions(7 to p1)))
        }
    }
}

/** spec §2.2·§3-2·§3-3: 이동 진입점 하나, 생성 = 위치 행 생성. */
class HwihaPositionWriteTest {
    private val hash = "c".repeat(64)
    private val p1 = StrategicNodeRef.LandProvince("p1")
    private val p2 = StrategicNodeRef.LandProvince("p2")
    private val p3 = StrategicNodeRef.LandProvince("p3")  // 城 없는 省
    private fun general(id: Int, city: Int) = TurnGeneral(id = id, name = "G$id", nationId = 0, cityId = city,
        troopId = 0, stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0,
        officerLevel = 0, turnTime = Instant.EPOCH)
    private fun world(profile: String = "HWIHA"): InMemoryTurnWorld {
        val positions = GeneralPositionSnapshot("r1", hash, setOf("p1", "p2", "p3"), emptySet())
            .withState(GeneralPositionState("r1", hash, 7, p1, 1))
        return InMemoryTurnWorld(WorldSnapshot(
            TurnWorldState(1, 200, 1, 60, Instant.EPOCH, config = mapOf("mapName" to "han-world-v3", "ruleProfile" to profile)),
            worldId = WorldId(1), generals = listOf(general(7, 10)), generalPositionSnapshot = positions,
            cityLandProvinceById = mapOf(10 to "p1", 20 to "p2"),
        ))
    }

    @Test
    fun `moving to a province with a city retargets the reference city`() {
        val world = world(); val recorder = ChangeRecorder()
        recorder.moveGeneral(world, 7, p2)
        assertEquals(20, world.getGeneralById(7)!!.cityId)
        assertEquals(p2, world.positionOf(7))
        assertTrue(world.isGeneralAtCity(7))
    }

    @Test
    fun `moving to a province without a city keeps the reference city and leaves the city`() {
        val world = world(); val recorder = ChangeRecorder()
        recorder.moveGeneral(world, 7, p3)
        assertEquals(10, world.getGeneralById(7)!!.cityId)  // 기준 城 유지 — 절대 0 이 되지 않는다
        assertEquals(p3, world.positionOf(7))
        assertFalse(world.isGeneralAtCity(7))
    }

    @Test
    fun `moveGeneral is refused outside hwiha worlds`() {
        val world = world("SAMMO"); val recorder = ChangeRecorder()
        assertFailsWith<IllegalStateException> { recorder.moveGeneral(world, 7, p2) }
    }

    @Test
    fun `creating a general creates its position row from the reference city`() {
        val world = world(); val recorder = ChangeRecorder()
        recorder.recordGeneralCreate(world, general(8, 20))
        assertEquals(p2, world.positionOf(8))
        assertTrue(world.isGeneralAtCity(8))
    }

    @Test
    fun `creating a general on a city without a province binding is refused, not skipped`() {
        val world = world(); val recorder = ChangeRecorder()
        assertFailsWith<IllegalStateException> { recorder.recordGeneralCreate(world, general(9, 99)) }
        assertEquals(null, world.getGeneralById(9))
        // 위치 쓰기가 거절돼도 장수가 반쪽으로 남지 않는다(삭제된 id 재사용 → UNKNOWN_GENERAL 거절 경로).
        recorder.recordGeneralCreate(world, general(11, 20))
        recorder.markGeneralDeleted(world, 11)
        assertFailsWith<IllegalStateException> { recorder.recordGeneralCreate(world, general(11, 20)) }
        assertEquals(null, world.getGeneralById(11))
        assertEquals(null, world.positionOf(11))
    }

    @Test
    fun `legacy city relocation in hwiha goes through moveGeneral and never deletes the row`() {
        val world = world(); val recorder = ChangeRecorder()
        applyPositionAwareGeneral(world, recorder, world.getGeneralById(7)!!.copy(cityId = 20))
        assertEquals(p2, world.positionOf(7))
        assertEquals(20, world.getGeneralById(7)!!.cityId)
        assertFailsWith<IllegalStateException> { recorder.removeGeneralPosition(world, 7) }
        assertEquals(p2, world.positionOf(7))
    }
}
