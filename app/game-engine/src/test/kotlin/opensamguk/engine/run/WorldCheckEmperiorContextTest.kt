package opensamguk.engine.run

import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.checkEmperior
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q14 천하통일 탐지의 엔진 시임([WorldCheckEmperiorContext]) 검증 — `func_gamerule.php:696-769`.
 * InMemoryTurnWorld read(level>0 국가/도시 소유) + isunited meta 전이 + 전토통일 nation-history 로그.
 * Docker 불요(in-memory). 컬럼 flush/boot-load 영속은 별도 갭(LEDGER 백로그)이라 본 테스트 범위 밖.
 */
class WorldCheckEmperiorContextTest {

    private fun world(
        meta: Map<String, Any?>,
        nations: List<Nation>,
        cities: List<City>,
    ) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 3600,
                lastTurnTime = Instant.EPOCH,
                meta = meta,
            ),
            nations = nations,
            cities = cities,
        ),
    )

    @Test
    fun `1국 전도시 소유 → meta isunited 2 + nation-history 전토통일 로그`() {
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = (1..3).map { City(id = it, name = "c$it", nationId = 7, level = 5) }
        val w = world(mapOf("isunited" to 0), nations, cities)

        checkEmperior(WorldCheckEmperiorContext(w))

        assertEquals(2, (w.getState().meta["isunited"] as Number).toInt())
        val logs = w.consumeDirtyState().logs
        assertEquals(
            1,
            logs.count {
                it.scope == "nation" && it.category == "history" &&
                    it.nationId == 7 && it.text == "<D><b>촉</b></>이 전토를 통일"
            },
        )
    }

    @Test
    fun `2국 잔존이면 no-op`() {
        val nations = listOf(
            Nation(id = 7, name = "촉", color = "#fff", level = 1),
            Nation(id = 8, name = "위", color = "#000", level = 1),
        )
        val cities = (1..4).map { City(id = it, name = "c$it", nationId = if (it <= 2) 7 else 8, level = 5) }
        val w = world(mapOf("isunited" to 0), nations, cities)

        checkEmperior(WorldCheckEmperiorContext(w))

        assertEquals(0, (w.getState().meta["isunited"] as Number).toInt())
        assertTrue(w.consumeDirtyState().logs.isEmpty())
    }

    @Test
    fun `1국이지만 공백지 잔존(전도시 미소유)이면 no-op`() {
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        // 도시 3개 중 1개는 공백지(nation=0) → 소유 도시 2 != 전체 3.
        val cities = listOf(
            City(id = 1, name = "c1", nationId = 7, level = 5),
            City(id = 2, name = "c2", nationId = 7, level = 5),
            City(id = 3, name = "c3", nationId = 0, level = 5),
        )
        val w = world(mapOf("isunited" to 0), nations, cities)

        checkEmperior(WorldCheckEmperiorContext(w))

        assertEquals(0, (w.getState().meta["isunited"] as Number).toInt())
        assertTrue(w.consumeDirtyState().logs.isEmpty())
    }
}
