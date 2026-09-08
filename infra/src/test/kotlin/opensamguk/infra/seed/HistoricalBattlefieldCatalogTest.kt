package opensamguk.infra.seed

import opensamguk.logic.world.StrategicNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class HistoricalBattlefieldCatalogTest {
    @Test
    fun `Guandu uses reconstructed Yuanwu ingress and its own source point`() {
        val entry = HistoricalBattlefieldCatalog.load().entries.getValue("guandu")
        assertEquals(51, entry.ingressCityId)
        assertEquals(StrategicNodeRef.LandProvince("82531"), entry.node)
        assertEquals(entry.node, HistoricalBattlefieldCatalog.cityAnchors().getValue(51))
        assertEquals("원무", HistoricalBattlefieldCatalog.cityName(51))
        val site = HistoricalBattlefieldCatalog.sites().single { it.id == "guandu" }
        assertEquals(34.75145, site.latitude)
        assertEquals(114.05092, site.longitude)
        assertEquals(198, site.sourceLine)
        val catalog = HistoricalBattlefieldCatalog.load()
        val request = opensamguk.logic.world.BattlefieldMovementRequest("r1", "a".repeat(64), 7, 51,
            null, null, catalog.contentHash, HistoricalBattlefieldCatalog.cityAnchors())
        val entered = assertIs<opensamguk.logic.world.BattlefieldMovementResult.Allowed>(
            opensamguk.logic.world.BattlefieldMovementRules.enter(catalog, "guandu", request))
        assertEquals("guandu", entered.assessment.battlefield?.siteId)
        assertEquals(51, entered.assessment.battlefield?.returnCityId)
    }

    @Test
    fun `playable Changban binds to the real Dangyang city and physical province`() {
        val catalog = HistoricalBattlefieldCatalog.load()
        val entry = catalog.entries.getValue("changban")
        assertEquals(405, entry.ingressCityId)
        assertEquals(StrategicNodeRef.LandProvince("45776"), entry.node)
        assertEquals(entry.node, HistoricalBattlefieldCatalog.cityAnchors().getValue(405))
        assertEquals(64, catalog.contentHash.length)
    }

    @Test
    fun `presence validation rejects wrong province and stale catalog`() {
        val catalog = HistoricalBattlefieldCatalog.load()
        val presence = opensamguk.logic.world.BattlefieldPresence("changban", catalog.contentHash, 405)
        HistoricalBattlefieldCatalog.validatePresence(StrategicNodeRef.LandProvince("45776"), presence)
        assertFailsWith<IllegalArgumentException> {
            HistoricalBattlefieldCatalog.validatePresence(StrategicNodeRef.LandProvince("other"), presence)
        }
        assertFailsWith<IllegalArgumentException> {
            HistoricalBattlefieldCatalog.validatePresence(StrategicNodeRef.LandProvince("45776"), presence.copy(catalogHash = "b".repeat(64)))
        }
    }
}
