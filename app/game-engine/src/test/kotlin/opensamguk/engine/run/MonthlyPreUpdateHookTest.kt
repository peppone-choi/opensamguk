package opensamguk.engine.run

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonthlyPreUpdateHookTest {

    @Test
    fun `월간 PRE는 PHP 순서의 상태 전이를 world와 recorder에 적용한다`() {
        // Given
        val world = world()
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val hook = MonthlyPreUpdateHook(world, recorder, profileName = "s1")

        // When
        val succeeded = hook.run()

        // Then
        assertTrue(succeeded)
        assertEquals(4, (world.getGeneralById(10)!!.meta["makelimit"] as Number).toInt())

        val nation = world.getNationById(1)!!
        assertEquals(2, (nation.meta["strategic_cmd_limit"] as Number).toInt())
        assertEquals(0, (nation.meta["surlimit"] as Number).toInt())
        assertEquals(17.5, (nation.meta["rate_tmp"] as Number).toDouble())
        assertEquals(linkedMapOf("2" to 2), nation.meta["spy"])

        val city = world.getCityById(2)!!
        assertEquals(31, city.state)
        assertEquals(0, city.term)
        assertEquals("{}", city.conflict)

        assertEquals(52, world.getState().meta["develcost"])
        assertEquals(990, world.getAccessLog(10)!!.refreshScoreTotal)
        assertEquals(990, recorder.accessLogUpserts().single().refreshScoreTotal)
        assertEquals(52, recorder.kvDirty()[KvKey("game_env", "game_env", "develcost")])
        assertTrue(recorder.generalPatches().any { it.id == 10 && it.meta["makelimit"] == 4 })
        assertTrue(recorder.nationPatches().any { it.id == 1 && it.meta["rate_tmp"] == 17.5 })
        assertTrue(recorder.cityPatches().any { it.id == 2 && it.columns["state"] == 31 })
    }

    @Test
    fun `월간 PRE는 상태 변경 전에 옛 날짜 연감 스냅샷을 기록한다`() {
        // Given
        val world = world()
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)

        // When
        MonthlyPreUpdateHook(world, recorder, profileName = "s1").run()

        // Then
        val history = recorder.yearbookInserts().single().columns
        assertEquals("s1", history["profile_name"])
        assertEquals(200, history["year"])
        assertEquals(4, history["month"])
        assertTrue((history["map"] as String).contains("\"year\":200"))
        assertTrue((history["nations"] as String).contains("\"name\":\"후한\""))
        assertEquals("[]", history["global_history"])
        assertEquals("[]", history["global_action"])
        assertFalse((history["hash"] as String).isBlank())
    }

    @Test
    fun `월간 PRE는 첩보가 없는 국가에 빈 spy 메타를 만들지 않는다`() {
        // Given
        val base = world()
        val nation = base.getNationById(1)!!
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = base.getState(),
                generals = base.listGenerals(),
                cities = base.listCities(),
                nations = listOf(nation.copy(meta = nation.meta - "spy")),
            ),
        )
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)

        // When
        MonthlyPreUpdateHook(world, recorder, profileName = "s1").run()

        // Then
        assertFalse(world.getNationById(1)!!.meta.containsKey("spy"))
        assertFalse(recorder.nationPatches().single().meta.containsKey("spy"))
    }

    @Test
    fun `catch-up 월경계는 이전 world 날짜를 반복하지 않고 월 커서를 진행한다`() {
        // Given
        val world = world()
        val recorder = ChangeRecorder(kvWriteObserver = world::applyKvDirtyFree)
        val hook = MonthlyPreUpdateHook(world, recorder, profileName = "s1")

        // When
        hook.run()
        hook.run()

        // Then
        assertEquals(
            listOf(200 to 4, 200 to 5),
            recorder.yearbookInserts().map { it.columns["year"] to it.columns["month"] },
        )
    }

    @Test
    fun `생산 월간 pipeline은 PRE true 스텁 대신 실제 어댑터를 배선한다`() {
        // Given
        val source = sequenceOf(
            File("src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
            File("app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
        ).firstOrNull { it.isFile } ?: error("DaemonLoopConfig.kt source not found")

        // When
        val text = source.readText()

        // Then
        assertTrue(text.contains("preUpdateMonthly = MonthlyPreUpdateHook("))
        assertFalse(text.contains("preUpdateMonthly = PreUpdateMonthly { true }"))
    }

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 4,
                tickSeconds = 3_600,
                lastTurnTime = Instant.parse("0200-04-01T00:00:00Z"),
                meta = linkedMapOf(
                    "startYear" to 184,
                    "map" to linkedMapOf("mapName" to "che"),
                ),
            ),
            generals = listOf(
                TurnGeneral(
                    id = 10,
                    name = "유비",
                    nationId = 1,
                    cityId = 2,
                    troopId = 0,
                    stats = GeneralStats(80, 70, 75),
                    experience = 0,
                    dedication = 0,
                    officerLevel = 12,
                    turnTime = Instant.parse("0200-04-01T00:00:00Z"),
                    meta = linkedMapOf("makelimit" to 5),
                ),
            ),
            accessLogs = listOf(
                GeneralAccessLog(
                    generalId = 10,
                    userId = 7,
                    lastRefresh = Instant.parse("0200-04-01T00:00:00Z"),
                    refreshScoreTotal = 1000,
                ),
            ),
            nations = listOf(
                Nation(
                    id = 1,
                    name = "후한",
                    color = "#c00",
                    capitalCityId = 2,
                    power = 100,
                    level = 1,
                    meta = linkedMapOf(
                        "strategic_cmd_limit" to 3,
                        "surlimit" to 0,
                        "rate" to 17.5,
                        "spy" to linkedMapOf("1" to 1, "2" to 3),
                    ),
                ),
            ),
            cities = listOf(
                City(
                    id = 2,
                    name = "낙양",
                    nationId = 1,
                    level = 5,
                    state = 32,
                    term = 1,
                    conflict = "{\"3\":1.05}",
                ),
            ),
        ),
    )
}
