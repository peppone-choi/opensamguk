package opensamguk.infra.seed

import opensamguk.logic.world.StrategicNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class HistoricalBattlefieldCatalogTest {
    @Test
    fun `expanded settlements preserve their distinct physical province anchors`() {
        val anchors = HistoricalBattlefieldCatalog.cityAnchors()
        for (id in (1134..1177).filterNot { it in setOf(1143, 1148, 1157, 1159, 1160, 1161, 1162, 1163, 1164, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194) }) {
            assertIs<StrategicNodeRef.LandProvince>(anchors.getValue(id))
        }
        assertEquals(35, ((1134..1177).filterNot { it in setOf(1143, 1148, 1157, 1159, 1160, 1161, 1162, 1163, 1164, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194) }).map { anchors.getValue(it) }.toSet().size)
    }

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
