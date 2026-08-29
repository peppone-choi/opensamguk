package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActiveWorldMapValidatorTest {

    @Test
    fun `exact compatibility city ids and positive references validate`() {
        ActiveWorldMapValidator.validate(snapshot("han-780-v1", 1..780, listOf(775), listOf(780)))
    }

    @Test
    fun `774 city snapshot cannot claim the compatibility map`() {
        val failure = assertFailsWith<IllegalStateException> {
            ActiveWorldMapValidator.validate(snapshot("han-780-v1", 1..774))
        }
        assertEquals(
            "worldId=1 mapName=han-780-v1 persisted city ids do not match variant",
            failure.message,
        )
    }

    @Test
    fun `positive unresolved general city fails but city zero is allowed`() {
        ActiveWorldMapValidator.validate(snapshot("han", 1..774, listOf(0)))
        val failure = assertFailsWith<IllegalStateException> {
            ActiveWorldMapValidator.validate(snapshot("han", 1..774, listOf(775)))
        }
        assertEquals("worldId=1 generalId=1 has unresolved cityId=775", failure.message)
    }

    @Test
    fun `positive unresolved nation capital fails but null capital is allowed`() {
        ActiveWorldMapValidator.validate(snapshot("han", 1..774, capitalCityIds = listOf(null)))
        val failure = assertFailsWith<IllegalStateException> {
            ActiveWorldMapValidator.validate(snapshot("han", 1..774, capitalCityIds = listOf(775)))
        }
        assertEquals("worldId=1 nationId=1 has unresolved capitalCityId=775", failure.message)
    }

    @Test
    fun `current 774 Han snapshot validates against current Han variant`() {
        ActiveWorldMapValidator.validate(snapshot("han", 1..774, listOf(774), listOf(1)))
        ActiveWorldMapValidator.validate(snapshot("han-world-v2", 1..774, listOf(774), listOf(1)))
    }

    private fun snapshot(
        mapName: String,
        cityIds: Iterable<Int>,
        generalCityIds: List<Int> = emptyList(),
        capitalCityIds: List<Int?> = emptyList(),
    ): WorldSnapshot {
        val now = Instant.parse("2026-08-29T00:00:00Z")
        return WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 184,
                currentMonth = 1,
                tickSeconds = 3600,
                lastTurnTime = now,
                config = mapOf("mapName" to mapName),
            ),
            cities = cityIds.map { id -> City(id = id, name = "city-$id", nationId = 0, level = 5) },
            generals = generalCityIds.mapIndexed { index, cityId ->
                TurnGeneral(
                    id = index + 1,
                    name = "general-${index + 1}",
                    nationId = 0,
                    cityId = cityId,
                    troopId = 0,
                    stats = GeneralStats(leadership = 50, strength = 50, intelligence = 50),
                    experience = 0,
                    dedication = 0,
                    officerLevel = 0,
                    turnTime = now,
                )
            },
            nations = capitalCityIds.mapIndexed { index, capitalCityId ->
                Nation(
                    id = index + 1,
                    name = "nation-${index + 1}",
                    color = "#000000",
                    capitalCityId = capitalCityId,
                )
            },
            worldId = WorldId(1),
        )
    }
}
