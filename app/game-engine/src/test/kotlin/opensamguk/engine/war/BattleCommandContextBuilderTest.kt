package opensamguk.engine.war

import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BO2 (engine adapter) — [BattleCommandContextBuilder] stages the battle context off the InMemoryTurnWorld:
 * the allowed-nation list (my-state-0 partners ∪ {me} ∪ {0}), the BFS distanceList, the per-city defender
 * generals (ascending PK), and the war-seed inputs — NO RNG, NO mutation.
 */
class BattleCommandContextBuilderTest {

    private val t0 = Instant.parse("0200-01-01T12:00:00Z")

    private fun general(id: Int, nationId: Int, cityId: Int) = TurnGeneral(
        id = id, name = "g$id", nationId = nationId, cityId = cityId, troopId = 0,
        stats = GeneralStats(80, 80, 80),
        experience = 0, dedication = 0, officerLevel = 0,
        crew = 5000, train = 100, atmos = 100, crewTypeId = 1100,
        turnTime = t0,
    )

    // Real CityConst ids: 업(1), 남피(9), 복양(18).
    private fun city(id: Int, nationId: Int) = City(id = id, name = "c$id", nationId = nationId, level = 5)

    private fun nation(id: Int, capital: Int) = Nation(id = id, name = "n$id", color = "#000", level = 1, capitalCityId = capital)

    @Test
    fun `builder stages distanceList over allowed nations and per-city defenders ascending`() {
        // My nation = 1 (city 업=1). Enemy nation = 2 (city 남피=9, the target) — diplomacy state=0 means
        // AT WAR (devsam: state 0 = 교전중), so 2 is an allowed/attackable nation. Nation 3 is ALSO at war
        // (state 0) and holds 복양=18 (so traversal flows through it).
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1, currentYear = 200, currentMonth = 6, tickSeconds = 3600, lastTurnTime = t0,
                    config = linkedMapOf("mapName" to "che"),
                ),
                generals = listOf(
                    general(id = 100, nationId = 1, cityId = 1),    // attacker
                    general(id = 7, nationId = 2, cityId = 9),       // defender (higher no)
                    general(id = 3, nationId = 2, cityId = 9),       // defender (lower no)
                    general(id = 50, nationId = 0, cityId = 9),      // neutral — excluded
                ),
                cities = listOf(
                    city(id = 1, nationId = 1),   // 업 (mine)
                    city(id = 9, nationId = 2),   // 남피 (enemy at war, target)
                    city(id = 18, nationId = 3),  // 복양 (also at war — traversable)
                ),
                nations = listOf(nation(1, 1), nation(2, 9), nation(3, 18)),
                diplomacy = listOf(
                    TurnDiplomacy(fromNationId = 1, toNationId = 2, state = 0, term = 0),  // at war w/ target
                    TurnDiplomacy(fromNationId = 1, toNationId = 3, state = 0, term = 0),  // at war (bridge)
                ),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 6, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )

        val ctx = BattleCommandContextBuilder.build(
            world = world,
            attackerGeneralId = 100,
            finalTargetCityId = 9,
            hiddenSeed = "0".repeat(32),
            loggerYear = 200,
            loggerMonth = 6,
        )

        assertEquals(1, ctx.attackerCityId)
        assertEquals(9, ctx.finalTargetCityId)
        assertEquals(1, ctx.myNationId)
        assertEquals(200, ctx.loggerYear)
        assertEquals(6, ctx.loggerMonth)

        // distanceList must place 남피(9) (the target=its own from-neighbour) and be non-empty.
        assertTrue(ctx.distanceList.isNotEmpty(), "distanceList must be populated over the allowed graph: ${ctx.distanceList}")
        val flat = ctx.distanceList.values.flatten()
        assertTrue(flat.any { it.first == 9 && it.second == 2 }, "남피(9) enemy must appear in the BFS: $flat")

        // per-city defenders staged ascending PK, neutral excluded.
        val defs = ctx.defenderGeneralsByCity[9]!!.map { it.id }
        assertEquals(listOf(3, 7), defs, "defender generals staged ascending PK, neutral (50) excluded")

        // raw rows present.
        assertEquals(2, ctx.cityById[9]!!.nationId)
        assertEquals(9, ctx.nationById[2]!!.capitalCityId)
    }

    @Test
    fun `allowed-nation list excludes not-at-war cities from the city map but still resolves target`() {
        // Target nation 2 IS at war (state 0) → attackable. A second nation 4 holding 복양(18) is NOT at war
        // (no state-0 row) → 복양 is NOT traversable. Target 남피(9) is a direct neighbour of 업(1), so it is
        // still reachable; assert 복양(18) is NOT in the BFS.
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1, currentYear = 200, currentMonth = 6, tickSeconds = 3600, lastTurnTime = t0,
                    config = linkedMapOf("mapName" to "che"),
                ),
                generals = listOf(general(id = 100, nationId = 1, cityId = 1)),
                cities = listOf(
                    city(id = 1, nationId = 1),
                    city(id = 9, nationId = 2),
                    city(id = 18, nationId = 4),  // not at war → blocked
                ),
                nations = listOf(nation(1, 1), nation(2, 9), nation(4, 18)),
                diplomacy = listOf(
                    TurnDiplomacy(fromNationId = 1, toNationId = 2, state = 0, term = 0),  // at war w/ target only
                ),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 6, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )

        val ctx = BattleCommandContextBuilder.build(
            world = world, attackerGeneralId = 100, finalTargetCityId = 9,
            hiddenSeed = "0".repeat(32), loggerYear = 200, loggerMonth = 6,
        )
        // 남피(9) reachable (direct neighbour); 복양(18) not in the allowed map → never bucketed.
        val flat = ctx.distanceList.values.flatten().map { it.first }
        assertTrue(9 in flat, "남피(9) target reachable as a direct from-neighbour: $flat")
        assertTrue(18 !in flat, "복양(18) is hostile/non-partner → excluded from the BFS: $flat")
    }
}
