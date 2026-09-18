package opensamguk.engine.turn

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
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
        val world = InMemoryTurnWorld(snapshot(null, listOf(general(7, 10)), null))
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
            InMemoryTurnWorld(snapshot("HWIHA", listOf(general(7, 10), general(8, 20)), positions(7 to p1)))
        }
    }

    @Test
    fun `hwiha boot fails when a reference city has no province binding`() {
        assertFailsWith<IllegalArgumentException> {
            InMemoryTurnWorld(snapshot("HWIHA", listOf(general(7, 99)), positions(7 to p1)))
        }
    }
}
