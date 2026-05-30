package opensamguk.logic.ai

import opensamguk.logic.domain.City
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-FACADE / Task FC1 — [AiWorldView] `categorizeNationCities` + `calcWarRoute` parity tests.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:3469-3513` (categorizeNationCities),
 * `:283-292` (calcWarRoute). NO RNG draws — these helpers only fix candidate ORDER.
 *
 * Load-bearing parity targets (B5, R-FACADE §1):
 *  - **PK-ascending HARD requirement:** the PHP query `SELECT * FROM city WHERE nation = %i` (`:3486`)
 *    has NO `ORDER BY` → MariaDB returns rows in clustered-PK (city id ascending) order. The Kotlin
 *    port MUST materialize `.sortedBy { it.cityId }` (ascending) into `LinkedHashMap`s in that exact
 *    order — a shuffled InMemory insertion order still yields cityId-ascending buckets.
 *  - the **4 buckets** (`:3497-3506`): `supplyCities` = supply truthy; `frontCities` = front truthy;
 *    `backupCities` = supply truthy AND NOT front (the `else if`); `nationCities` = ALL.
 *  - `dev` float = `(agri+comm+secu+def+wall) / (agri_max+comm_max+secu_max+def_max+wall_max)` (`:3489-3491`),
 *    `important = 1`, empty `generals` list (filled later by-ref by categorizeNationGeneral, FC2).
 *  - `calcWarRoute` (`:283-292`): `target = array_keys(warTargetNation ?? []); target[] = ownNation`;
 *    the `[...warTargets, ownNation]` APPEND order is a tie-ordering parity target.
 *  - both lazy-once (the `if (... !== null) return;` null-guards, `:3472`/`:285`) — NOT re-invalidated.
 */
class AiWorldViewCitiesTest {

    private fun city(
        id: Int,
        nation: Int,
        supply: Int = 1,
        front: Int = 0,
        agri: Int = 0, agriMax: Int = 0,
        comm: Int = 0, commMax: Int = 0,
        secu: Int = 0, secuMax: Int = 0,
        def: Int = 0, defMax: Int = 0,
        wall: Int = 0, wallMax: Int = 0,
    ): City = City(
        id = id, nationId = nation, level = 5,
        commerce = comm, commerceMax = commMax,
        agriculture = agri, agricultureMax = agriMax,
        supplyState = supply, frontState = front, trust = 0.0,
        security = secu, securityMax = secuMax,
        defense = def, defenseMax = defMax,
        wall = wall, wallMax = wallMax,
    )

    // ----------------------------------------------------------------------------------------------
    // (1) PK-ascending HARD requirement — shuffled insertion still yields cityId-ascending buckets.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `categorizeNationCities materializes PK-ascending order even from shuffled insertion`() {
        // Deliberately supply rows OUT of cityId order to prove the explicit sortedBy{cityId} fires.
        val rows = listOf(
            city(53, nation = 1),
            city(14, nation = 1),
            city(30, nation = 1),
            city(99, nation = 2), // foreign — excluded
        )
        val view = AiWorldView(ownNationId = 1, cityRows = rows, warTargetNation = null)
        view.categorizeNationCities()

        assertEquals(
            listOf(14, 30, 53),
            view.nationCities.keys.toList(),
            "nationCities keyed cityId-ascending (PK order) regardless of insertion order",
        )
        assertEquals(listOf(14, 30, 53), view.supplyCities.keys.toList())
        assertEquals(listOf(14, 30, 53), view.backupCities.keys.toList()) // all supply, none front
        assertTrue(view.frontCities.isEmpty())
    }

    // ----------------------------------------------------------------------------------------------
    // (2) The 4 buckets — supply / front / backup (= supply AND NOT front) / all.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `the four buckets partition correctly supply front backup all`() {
        val rows = listOf(
            city(1, nation = 1, supply = 1, front = 1), // supply + front
            city(2, nation = 1, supply = 1, front = 0), // supply, NOT front -> backup
            city(3, nation = 1, supply = 0, front = 1), // NOT supply, front
            city(4, nation = 1, supply = 0, front = 0), // neither
        )
        val view = AiWorldView(ownNationId = 1, cityRows = rows, warTargetNation = null)
        view.categorizeNationCities()

        assertEquals(listOf(1, 2, 3, 4), view.nationCities.keys.toList(), "nationCities = ALL")
        assertEquals(listOf(1, 2), view.supplyCities.keys.toList(), "supplyCities = supply truthy")
        assertEquals(listOf(1, 3), view.frontCities.keys.toList(), "frontCities = front truthy")
        // backupCities = supply truthy AND NOT front (the `else if` — city 1 is front so excluded).
        assertEquals(listOf(2), view.backupCities.keys.toList(), "backupCities = supply AND NOT front")
    }

    // ----------------------------------------------------------------------------------------------
    // (3) dev float, important=1, empty generals list.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `each city gets dev float important=1 and an empty generals list`() {
        val rows = listOf(
            city(
                7, nation = 1,
                agri = 100, agriMax = 200,
                comm = 50, commMax = 100,
                secu = 10, secuMax = 40,
                def = 20, defMax = 40,
                wall = 30, wallMax = 60,
            ),
        )
        val view = AiWorldView(ownNationId = 1, cityRows = rows, warTargetNation = null)
        view.categorizeNationCities()

        val c = view.nationCities.getValue(7)
        // dev = (100+50+10+20+30) / (200+100+40+40+60) = 210 / 440
        assertEquals(210.0 / 440.0, c.dev, 1e-12)
        assertEquals(1, c.important)
        assertTrue(c.generals.isEmpty(), "generals list starts empty (filled by-ref in FC2)")
        assertEquals(7, c.cityId)
    }

    // ----------------------------------------------------------------------------------------------
    // (4) lazy-once — categorizeNationCities runs once, not re-invalidated.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `categorizeNationCities is lazy-once and idempotent`() {
        var supplierCalls = 0
        val view = AiWorldView(
            ownNationId = 1,
            cityRowsSupplier = { supplierCalls += 1; listOf(city(14, nation = 1)) },
            warTargetNation = null,
        )
        view.categorizeNationCities()
        view.categorizeNationCities()
        assertEquals(1, supplierCalls, "the PK-ascending row snapshot is materialized exactly once")
    }

    // ----------------------------------------------------------------------------------------------
    // (5) calcWarRoute — target append order [warTargets..., ownNation]; lazy-once; BFS distances.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `calcWarRoute appends ownNation after warTargets and runs the nation-list BFS`() {
        // warTargetNation = {2 -> 2}; ownNation = 1. target = [2, 1] (warTargets then ownNation).
        // nation 2 owns 30; nation 1 owns 54. searchAllDistanceByNationList over PK-ascending rows.
        val cityRows = listOf(
            city(30, nation = 2),
            city(54, nation = 1),
        )
        val view = AiWorldView(
            ownNationId = 1,
            cityRows = cityRows,
            warTargetNation = linkedMapOf(2 to 2),
        )
        view.calcWarRoute()

        val route = view.warRoute
        assertNotNull(route)
        // cityIDList from nations [2,1] over PK-ascending rows [30(n2), 54(n1)] = [30, 54].
        // 30 and 54 are adjacent (30's path includes 54). Floyd rows keyed [30, 54].
        assertEquals(listOf(30, 54), route.keys.toList())
        assertEquals(mapOf(30 to 0, 54 to 1), route.getValue(30))
        assertEquals(mapOf(54 to 0, 30 to 1), route.getValue(54))
    }

    @Test
    fun `calcWarRoute with null warTargetNation routes over ownNation only`() {
        // target = array_keys(null ?? []) = [] then [] + ownNation = [1].
        val cityRows = listOf(city(54, nation = 1), city(30, nation = 1))
        val view = AiWorldView(ownNationId = 1, cityRows = cityRows, warTargetNation = null)
        view.calcWarRoute()

        val route = view.warRoute
        assertNotNull(route)
        // nation 1 owns [30, 54] (PK-ascending). Both adjacent.
        assertEquals(listOf(30, 54), route.keys.toList())
    }

    @Test
    fun `calcWarRoute preserves warTargets-before-ownNation append (own nation appears last in candidate set)`() {
        // warTargetNation = {2 -> 2}; ownNation = 1. The nation list passed to BFS = [2, 1].
        // City rows are still PK-ascending; the cityIDList is PK-ascending within the filter, but the
        // NATION-list append order [warTargets..., ownNation] is the parity target the helper threads.
        val cityRows = listOf(city(30, nation = 2), city(54, nation = 1))
        val view = AiWorldView(ownNationId = 1, cityRows = cityRows, warTargetNation = linkedMapOf(2 to 2))
        assertEquals(listOf(2, 1), view.warRouteNationList(), "target = [warTargets..., ownNation]")
    }

    @Test
    fun `calcWarRoute is lazy-once`() {
        var supplierCalls = 0
        val view = AiWorldView(
            ownNationId = 1,
            cityRowsSupplier = { supplierCalls += 1; listOf(city(54, nation = 1)) },
            warTargetNation = null,
        )
        view.calcWarRoute()
        view.calcWarRoute()
        assertEquals(1, supplierCalls, "warRoute computed once; the row snapshot fetched once")
    }
}
