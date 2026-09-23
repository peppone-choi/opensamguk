package opensamguk.engine.hwiha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation

class HwihaCapitalAfterCaptureTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val counties = fixture.bundle.projection.administrativeCountyIds.sorted()

    @Test fun `lost capital moves to the largest remaining county with id as tie break`() {
        val captured = counties[0]; val smaller = counties[1]; val larger = counties[2]
        val world = fixture.world(listOf(fixture.person(1, 1, captured) to fixture.route().start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = captured, level = 1, chiefGeneralId = 1), Nation(2, "N2", "#222222")),
            cityChanges = { city -> when (city.id) {
                captured -> city.copy(nationId = 2)
                smaller -> city.copy(nationId = 1, population = 5000)
                larger -> city.copy(nationId = 1, population = 9000)
                else -> city
            } })
        HwihaCapitalAfterCapture(world, ChangeRecorder()).settle(1, captured)
        assertEquals(larger, world.getNationById(1)?.capitalCityId)
        assertTrue(world.getCityById(world.getNationById(1)!!.capitalCityId!!)?.nationId == 1)
        val supply = HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells)
        assertTrue(supply.recomputeSupply(world, ChangeRecorder(), emptySet()) >= 0)
        assertEquals(1, world.getCityById(larger)?.supplyState, "the new own capital is the supply root")
    }

    @Test fun `nation with no county follows the existing extinction cascade`() {
        val captured = counties[0]
        val world = fixture.world(listOf(fixture.person(1, 1, captured) to fixture.route().start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = captured, chiefGeneralId = 1), Nation(2, "N2", "#222222")),
            cityChanges = { city -> if (city.id == captured) city.copy(nationId = 2) else city })
        HwihaCapitalAfterCapture(world, ChangeRecorder()).settle(1, captured)
        assertNull(world.getNationById(1))
        assertEquals(0, world.getGeneralById(1)?.nationId)
    }
}
