package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BO1 — port target = PHP `process_war.php:192-226` (`extractBattleOrder`) + the defender candidate ordering
 * (`:36-61`).
 *
 * Pins:
 *  - the FOUR ZERO gates (crew==0, rice<=crew/100, train<defence_train, atmos<defence_train) → 0;
 *  - totalStat = (realStat + fullStat)/2 via getStatValue;
 *  - totalCrew = crew/1e6 * (train*atmos)^1.5 FLOAT, /100 added;
 *  - the defender list explicitly `sortedBy { it.no }` BEFORE the usort, then sorted DESC by the FLOAT order
 *    with a STABLE secondary key (ascending `no`) — two equal-order candidates assert the lower `no` first
 *    (PR-4, asserted DIRECTLY, not via a golden);
 *  - the city is appended only when the list is non-empty AND its order>0.
 */
class ExtractBattleOrderTest {

    private val pipeline = GeneralActionPipeline()
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!
    private val seed = ByteArray(32) { it.toByte() }
    private fun rng(): RandUtil = RandUtil(LiteHashDrbg(seed))

    private fun general(
        id: Int = 1,
        crew: Int = 5000,
        rice: Int = 10000,
        train: Double = 100.0,
        atmos: Double = 100.0,
        lead: Int = 80,
        str: Int = 80,
        intel: Int = 80,
        injury: Int = 0,
        defenceTrain: Int = 0,
    ): General = General(
        id = id, nationId = 1, cityId = 10,
        leadership = lead, strength = str, intel = intel, injury = injury,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = rice,
        crew = crew, train = train, atmos = atmos, crewTypeId = 1100,
        meta = linkedMapOf("explevel" to 0, "defence_train" to defenceTrain),
    )

    private fun warGeneral(g: General, attacker: Boolean, rng: RandUtil): WarUnitGeneral =
        WarUnitGeneral(
            rng = rng, state = WarUnitGeneralState(g), pipeline = pipeline,
            crewType = footman, tech = 0, isAttacker = attacker, cityLevel = 9, isCapital = false,
        )

    private fun warCity(rng: RandUtil): WarUnitCity {
        val city = City(
            id = 10, nationId = 0, level = 5,
            commerce = 1000, commerceMax = 9999,
            agriculture = 1000, agricultureMax = 9999,
            supplyState = 1, frontState = 0, trust = 100.0,
            security = 1000, securityMax = 9999,
            defense = 500, defenseMax = 9999,
            wall = 500, wallMax = 9999,
            population = 100000, populationMax = 999999,
        )
        return WarUnitCity(rng = rng, state = WarUnitCityState(city), year = 200, startYear = 180)
    }

    @Test
    fun `crew zero gate returns 0`() {
        val r = rng()
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        val def = warGeneral(general(id = 2, crew = 0), attacker = false, rng = r)
        assertEquals(0.0, extractBattleOrder(def, attacker))
    }

    @Test
    fun `rice at or below crew over 100 gate returns 0`() {
        val r = rng()
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        // crew=5000 → crew/100 = 50; rice=50 is NOT > 50 → gate fires.
        val def = warGeneral(general(id = 2, crew = 5000, rice = 50), attacker = false, rng = r)
        assertEquals(0.0, extractBattleOrder(def, attacker))
    }

    @Test
    fun `train below defence_train gate returns 0`() {
        val r = rng()
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        val def = warGeneral(general(id = 2, train = 50.0, defenceTrain = 80), attacker = false, rng = r)
        assertEquals(0.0, extractBattleOrder(def, attacker))
    }

    @Test
    fun `atmos below defence_train gate returns 0`() {
        val r = rng()
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        val def = warGeneral(general(id = 2, atmos = 50.0, defenceTrain = 80), attacker = false, rng = r)
        assertEquals(0.0, extractBattleOrder(def, attacker))
    }

    @Test
    fun `general order is totalStat plus crewfloat over 100`() {
        val r = rng()
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        val def = warGeneral(general(id = 2, crew = 5000, train = 100.0, atmos = 100.0), attacker = false, rng = r)

        val realStat = def.getRealStatSum()
        val fullStat = def.getFullStatSum()
        val totalStat = (realStat + fullStat) / 2
        val totalCrew = 5000 / 1_000_000.0 * Math.pow(100.0 * 100.0, 1.5)
        val expected = totalStat + totalCrew / 100

        assertEquals(expected, extractBattleOrder(def, attacker), 1e-9)
        assertTrue(expected > 0)
    }

    @Test
    fun `defender list sorts DESC by float order with ascending-no stable tie-break`() {
        val r = rng()
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        // Two equal-order defenders (identical stats/crew) with ids 7 and 3 — the float order ties exactly.
        val defHi = warGeneral(general(id = 9, crew = 9000), attacker = false, rng = r)   // higher order
        val defTieB = warGeneral(general(id = 7, crew = 5000), attacker = false, rng = r) // tie, higher no
        val defTieA = warGeneral(general(id = 3, crew = 5000), attacker = false, rng = r) // tie, lower no

        // Input is intentionally NOT pre-sorted by no; orderDefenders must sort ascending-no FIRST.
        val input: List<WarUnitGeneral> = listOf(defTieB, defHi, defTieA)
        val ordered = orderDefenders(input, attacker, city = null)

        // DESC by order: defHi first (biggest crew → biggest order); the two ties next, lower no (3) before 7.
        assertEquals(listOf(9, 3, 7), ordered.map { (it as WarUnitGeneral).no })
    }

    @Test
    fun `city appended only when list non-empty and city order positive`() {
        val r = rng()
        // cityBattleOrder default fold (no modules) returns -1 → city order NOT > 0 → never appended.
        val attacker = warGeneral(general(id = 1), attacker = true, rng = r)
        val def = warGeneral(general(id = 2), attacker = false, rng = r)
        val city = warCity(r)

        // empty list: city not appended even if order were positive.
        assertEquals(0, orderDefenders(emptyList(), attacker, city).size)

        // non-empty list, but city order = -1 (<=0) → not appended.
        val ordered = orderDefenders(listOf(def), attacker, city)
        assertEquals(1, ordered.size)
        assertTrue(ordered.none { it is WarUnitCity })
    }
}
