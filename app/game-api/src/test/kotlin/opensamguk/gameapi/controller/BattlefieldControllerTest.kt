package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.HistoricalBattlefieldCatalog
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattlefieldControllerTest {
    private val resolver = mock(GeneralResolver::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val positions = mock(BattlefieldReadRepository::class.java)
    private val controller get() = BattlefieldController(resolver, world, positions)
    private fun arrange(cityId: Int = 405, position: GeneralPositionState? = null) {
        val general = GeneralReadEntity(id = 10, name = "장수", cityId = cityId, nationId = 1, crew = 1000)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(general, 1, 0, 1, 3))
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = mapOf("mapName" to "han-world-v3")))
        `when`(positions.read(10)).thenReturn(BattlefieldReadState("r1", "a".repeat(64), position,
            HistoricalBattlefieldCatalog.cityAnchors()))
    }
    @Test fun `anonymous and unowned character cannot query private position`() {
        assertEquals(401, controller.battlefields(null).statusCode.value())
        assertEquals(404, controller.battlefields(7L).statusCode.value())
        verifyNoInteractions(positions)
    }
    @Test fun `entry eligibility follows actual origin and exposes no enemy occupants`() {
        arrange()
        val body = controller.battlefields(7L).body as BattlefieldsResponse
        assertTrue(body.sites.single { it.id == "changban" }.canEnter)
        assertEquals("", body.positionRevision)
        arrange(cityId = 1)
        assertFalse((controller.battlefields(7L).body as BattlefieldsResponse).sites.single { it.id == "changban" }.canEnter)
    }
    @Test fun `deployed general receives return availability and current site`() {
        val catalog = HistoricalBattlefieldCatalog.load()
        arrange(position = GeneralPositionState("r1", "a".repeat(64), 10, StrategicNodeRef.LandProvince("45776"), 2,
            BattlefieldPresence("changban", catalog.contentHash, 405)))
        val body = controller.battlefields(7L).body as BattlefieldsResponse
        assertEquals("changban", body.currentSiteId)
        assertEquals("2", body.positionRevision)
        assertTrue(body.canExit)
        assertFalse(body.sites.single { it.id == "changban" }.canEnter)
    }
    @Test fun `Guandu entry follows Yuanwu ingress and names that origin in denial`() {
        arrange(cityId = 51)
        val body = controller.battlefields(7L).body as BattlefieldsResponse
        assertTrue(body.sites.single { it.id == "guandu" }.canEnter)
        assertFalse(body.sites.single { it.id == "changban" }.canEnter)
        arrange(cityId = 405)
        val denied = (controller.battlefields(7L).body as BattlefieldsResponse).sites.single { it.id == "guandu" }
        assertFalse(denied.canEnter)
        assertEquals("원무에 있는 부대만 진입할 수 있습니다.", denied.reason)
    }

}
