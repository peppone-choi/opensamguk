package opensamguk.logic.war

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BC1 — ConquerCity setup: the double-seed RESET + the OccupyCity event SLOT + the defender
 * `onArbitraryAction` collapse loop (the FIRST consumer of the reset rng).
 *
 * Port target = PHP `process_war.php:532-603`:
 *  - SEED #1 build (`:549`) drives the conquest side-effects up to the OccupyCity event;
 *  - the OccupyCity EventTarget handler SLOT (`:586-588`) — a no-op in P4 (no city-conquest events), but
 *    the slot + ordering are preserved;
 *  - SEED #2 REBUILD (`:589`) — the rng is RE-CONSTRUCTED fresh with the IDENTICAL seed → the stream
 *    RESETS to idx 0; the OccupyCity handler's draws do NOT advance the ConquerCity stream;
 *  - the defender-city general loop (`:598-603`) `onArbitraryAction(self, rng, 'ConquerCity', null,
 *    ['attacker'=>general])` — the FIRST consumer of the reset rng; iteration MUST be explicit
 *    ascending PK (`sortedBy { it.no }`), NOT query order (PR-4).
 *
 * If no ConquerCity-target triggers are ported yet, this loop draws ZERO — but the loop MUST exist as the
 * structural first-consumer slot so the collapse draws don't shift when triggers are added (OQ #12).
 */
class ConquerCitySetupTest {

    private val hidden = "ab".repeat(16)

    private fun attacker(id: Int = 1, nationId: Int = 10): General = General(
        id = id, nationId = nationId, cityId = 100,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5,
        gold = 1000, rice = 10000,
    )

    private fun city(id: Int = 200, nationId: Int = 20, level: Int = 5): City = City(
        id = id, nationId = nationId, level = level,
        commerce = 1000, commerceMax = 9999,
        agriculture = 1000, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = 1000, securityMax = 9999,
        defense = 800, defenseMax = 2000,
        wall = 800, wallMax = 2000,
        population = 100000, populationMax = 999999,
        conflict = "{}",
    )

    private fun defenderGeneral(id: Int, nationId: Int = 20): General = General(
        id = id, nationId = nationId, cityId = 200,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 500, rice = 500,
    )

    private fun input(
        defenderCity: City = city(),
        defenderNation: Nation? = Nation(id = 20, level = 5, capitalCityId = 999),
        defenderCityGenerals: List<General> = emptyList(),
        nationCityCount: Int = 3,
    ) = ConquerCityInput(
        admin = ConquerAdmin(hiddenSeed = hidden, year = 200, month = 6, joinMode = "normal"),
        attacker = attacker(),
        defenderCity = defenderCity,
        defenderNation = defenderNation,
        defenderCityGenerals = defenderCityGenerals,
        defenderNationCityCount = nationCityCount,
        defenderNationGenerals = emptyList(),
        allCitiesForBfs = emptyList(),
    )

    /** A recording arbitrary-action seam: captures the per-defender call order + how many draws each made. */
    private class RecordingArbitrary(private val drawsPerGeneral: Int = 0) : ConquerArbitraryAction {
        val order = mutableListOf<Int>()
        override fun onArbitraryAction(defender: General, rng: RandUtil, attacker: General) {
            order.add(defender.id)
            repeat(drawsPerGeneral) { rng.nextBool(0.5) }
        }
    }

    @Test
    fun `seed 1 and seed 2 are built with identical args`() {
        val s1 = ConquerCitySeed.seed(hidden, 200, 6, 10, 1, 200)
        // The resolver's two builds (process_war.php:549 and :589) use the SAME 7-arg tuple.
        val s2 = ConquerCitySeed.seed(hidden, 200, 6, 10, 1, 200)
        assertEquals(s1, s2, "the double-seed builds the IDENTICAL 7-arg ConquerCity token")
    }

    @Test
    fun `after the OccupyCity event the rng RESETS to idx 0 - first collapse draw at position 0`() {
        // Build the reference stream from a fresh rng (the SEED #2 state) and capture its first draw.
        val refRng = ConquerCitySeed.rng(hidden, 200, 6, 10, 1, 200)
        val firstDrawOfFreshStream = refRng.nextBool(0.5)

        // Run the resolver with an OccupyCity handler that DRAWS on SEED #1 (advancing it), then the
        // defender loop must see a FRESH stream (idx 0) — its first draw == the fresh-stream first draw.
        val rec = RecordingArbitrary(drawsPerGeneral = 1)
        val res = ConquerCity.resolve(
            input(defenderCityGenerals = listOf(defenderGeneral(7))),
            arbitraryAction = rec,
            occupyCityHandler = { rng -> rng.nextBool(0.5); rng.nextRangeInt(0, 100) }, // draws on SEED #1
        )
        // The defender loop's first general's first draw came off the RESET stream (idx 0).
        assertEquals(firstDrawOfFreshStream, res.firstCollapseDraw, "the rng RESETS to idx 0 after OccupyCity")
    }

    @Test
    fun `defender loop iterates ascending general id - NOT input order`() {
        // Input deliberately out of PK order; the loop MUST sort ascending by no (PR-4).
        val rec = RecordingArbitrary()
        ConquerCity.resolve(
            input(defenderCityGenerals = listOf(defenderGeneral(9), defenderGeneral(3), defenderGeneral(7))),
            arbitraryAction = rec,
        )
        assertEquals(listOf(3, 7, 9), rec.order, "the defender loop pins ascending PK iteration")
    }

    @Test
    fun `an empty trigger set draws ZERO in the loop - the slot still exists`() {
        // The recording seam draws nothing (no ConquerCity-target triggers ported) — but the loop iterates.
        val rec = RecordingArbitrary(drawsPerGeneral = 0)
        val res = ConquerCity.resolve(
            input(defenderCityGenerals = listOf(defenderGeneral(5), defenderGeneral(8))),
            arbitraryAction = rec,
        )
        assertEquals(listOf(5, 8), rec.order, "the structural first-consumer slot iterates all defenders")
        assertEquals(0, res.collapseLoopDraws, "an empty trigger set draws ZERO in the loop")
        assertTrue(res.conquerLogs.isNotEmpty(), "the 공략 성공 / 지배 logs are emitted")
    }
}
