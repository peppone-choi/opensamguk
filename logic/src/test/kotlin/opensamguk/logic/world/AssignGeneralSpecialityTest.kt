package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A6b — AssignGeneralSpeciality (PHP `Event/Action/AssignGeneralSpeciality.php`), a gate-byte-matched
 * `$defaultEvents` leaf firing at month==1 (priority 9000, LAST in the January row). Self-seeds its OWN
 * DRBG (it does NOT borrow `$monthlyRng`).
 *
 * Port targets (PHP grand truth):
 *   - `if ($year < $startYear + 3) return;` — early return BEFORE any RNG construction (no draw consumed).
 *   - seed = simpleSerialize(hiddenSeed, 'assignGeneralSpeciality', year, month).
 *   - domestic loop: `SELECT ... FROM general WHERE specage<=age AND special=defaultSpecialDomestic` (PK asc);
 *     each picks via SpecialityHelper.pickSpecialDomestic(rng, general, aux.prev_types_special).
 *   - war loop: `... WHERE specage2<=age AND special2=defaultSpecialWar` (PK asc); aux.inheritSpecificSpecialWar
 *     short-circuits the pick (no RNG); else SpecialityHelper.pickSpecialWar(rng, general, aux.prev_types_special2).
 *   - per-general RNG draw order = PK ascending; the domestic loop fully precedes the war loop.
 */
class AssignGeneralSpecialityTest {

    private val HIDDEN_SEED = "00000000000000000000000000000000"

    private fun rng(year: Int, month: Int) =
        RandUtil(LiteHashDrbg(serializeSeed(HIDDEN_SEED, "assignGeneralSpeciality", year, month)))

    private fun gen(
        no: Int, age: Int = 40, specage: Int = 30, specage2: Int = 30,
        special: String = "None", special2: String = "None",
        leadership: Int = 70, strength: Int = 70, intel: Int = 70,
        aux: Map<String, Any?> = emptyMap(),
    ) = SpecialityGeneral(
        no = no, name = "장수$no", nation = 1, age = age, specage = specage, specage2 = specage2,
        special = special, special2 = special2,
        leadership = leadership, strength = strength, intel = intel,
        dex1 = 0, dex2 = 0, dex3 = 0, dex4 = 0, dex5 = 0, npc = 0, aux = aux,
    )

    // ── early return: year < startYear + 3 → no-op, NO RNG draw ───────────────
    @Test
    fun `year before startYear+3 returns early with no assignments and no RNG draw`() {
        // startYear 181 → fires from 184; year 183 < 184 → early return.
        val r = assignGeneralSpeciality(
            listOf(gen(1), gen(2)), year = 183, month = 1, startYear = 181, rng = rng(183, 1))
        assertTrue(r.skippedByYearGate, "year < startYear+3 → skipped")
        assertTrue(r.domesticAssignments.isEmpty() && r.warAssignments.isEmpty(),
            "skip window → no assignments")
    }

    @Test
    fun `at exactly startYear+3 the gate opens`() {
        val r = assignGeneralSpeciality(
            listOf(gen(1, special = "None", special2 = "che_보병")), // domestic-eligible only
            year = 184, month = 1, startYear = 181, rng = rng(184, 1))
        assertTrue(!r.skippedByYearGate, "year == startYear+3 → NOT skipped")
    }

    // ── eligible-general filter ───────────────────────────────────────────────
    @Test
    fun `domestic loop only touches generals with specage lte age AND special == None`() {
        val cities = listOf(
            gen(1, age = 40, specage = 30, special = "None"),       // eligible
            gen(2, age = 40, specage = 50, special = "None"),       // specage 50 > age 40 → NOT eligible
            gen(3, age = 40, specage = 30, special = "che_경작"),    // already has a special → NOT eligible
        )
        val r = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        val touched = r.domesticAssignments.map { it.generalId }.toSet()
        assertEquals(setOf(1), touched, "only general 1 is domestic-eligible")
    }

    @Test
    fun `war loop only touches generals with specage2 lte age AND special2 == None`() {
        val cities = listOf(
            gen(1, age = 40, specage2 = 30, special = "che_경작", special2 = "None"), // war-eligible (domestic already set)
            gen(2, age = 40, specage2 = 99, special = "che_경작", special2 = "None"), // specage2>age → NOT
            gen(3, age = 40, specage2 = 30, special = "che_경작", special2 = "che_보병"), // special2 set → NOT
        )
        val r = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        val touched = r.warAssignments.map { it.generalId }.toSet()
        assertEquals(setOf(1), touched, "only general 1 is war-eligible")
    }

    // ── deterministic + PK-ascending ──────────────────────────────────────────
    @Test
    fun `same seed and same general order yields identical assignments`() {
        val cities = listOf(
            gen(1, special = "None", special2 = "None"),
            gen(2, special = "None", special2 = "None"),
            gen(3, special = "None", special2 = "None"),
        )
        val a = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        val b = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        assertEquals(a.domesticAssignments, b.domesticAssignments)
        assertEquals(a.warAssignments, b.warAssignments)
    }

    @Test
    fun `domestic assignments recorded in PK-ascending general order`() {
        val cities = listOf(gen(5), gen(2), gen(9)) // caller supplies PK order; here already sorted-by-supply
        val r = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        assertEquals(listOf(5, 2, 9), r.domesticAssignments.map { it.generalId },
            "assignments follow the supplied (PK) iteration order")
    }

    @Test
    fun `every picked domestic special is one of the available domestic specials`() {
        val available = setOf(
            "che_경작", "che_상재", "che_발명", "che_축성", "che_수비", "che_통찰", "che_인덕", "che_귀모")
        val cities = (1..20).map { gen(it, leadership = 60 + it % 30, strength = 50 + it % 40, intel = 55 + it % 35) }
        val r = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        for (a in r.domesticAssignments) {
            assertTrue(a.special in available, "picked ${a.special} must be an available domestic special")
        }
    }

    // ── inheritSpecificSpecialWar short-circuits the war pick (no RNG) ─────────
    @Test
    fun `war loop honors inheritSpecificSpecialWar without an RNG pick`() {
        val cities = listOf(
            gen(1, special = "che_경작", special2 = "None",
                aux = mapOf("inheritSpecificSpecialWar" to "che_보병")),
        )
        val r = assignGeneralSpeciality(cities, year = 200, month = 1, startYear = 181, rng = rng(200, 1))
        assertEquals(1, r.warAssignments.size)
        assertEquals("che_보병", r.warAssignments[0].special,
            "inheritSpecificSpecialWar forces the war special verbatim")
        assertTrue(r.warAssignments[0].inheritedFromAux, "marked as inherited (no RNG pick)")
    }

    // ── prev_types_special is respected (avoid repeats when possible) ─────────
    @Test
    fun `prev_types_special excludes the named domestic special when alternatives exist`() {
        // A general whose every-stat is high (all conds open); exclude 귀모 → never picks che_귀모.
        val cities = (1..40).map {
            gen(it, leadership = 90, strength = 90, intel = 90,
                aux = mapOf("prev_types_special" to listOf("che_귀모")))
        }
        val r = assignGeneralSpeciality(cities, year = 205, month = 1, startYear = 181, rng = rng(205, 1))
        assertTrue(r.domesticAssignments.none { it.special == "che_귀모" },
            "che_귀모 is excluded by prev_types_special (alternatives exist)")
    }
}
