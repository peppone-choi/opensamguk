package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.web.DomesticController
import org.mockito.Mockito.*
import java.util.Optional
import kotlin.test.*

class DomesticReaderOwnershipTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val geography = mock(CityGeography::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val sieges = mock(SiegeReadRepository::class.java)
    private val reader = DomesticReader(generals, retainers, nations, artifacts, spatial, geography,
        gameKv, ObjectMapper(), diplomacy, sieges)
    private val controller = DomesticController(mock(CommandReserveService::class.java), reader)

    @Test fun `posts deny anonymous foreign and missing actors before reading card data`() {
        val actor = GeneralReadEntity(id = 1, worldId = 1, userId = "41", npcState = 2)
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(generals.findById(99)).thenReturn(Optional.empty())
        for (userId in listOf(null, 0L, Int.MAX_VALUE.toLong() + 1))
            assertEquals(401, controller.posts(userId, 1).statusCode.value())
        verifyNoInteractions(generals)
        assertEquals(403, controller.posts(42, 1).statusCode.value())
        assertEquals(403, controller.posts(41, 99).statusCode.value())
        verifyNoInteractions(retainers, nations, artifacts, spatial, geography, gameKv, diplomacy, sieges)
        assertEquals(200, controller.posts(41, 1).statusCode.value())
        verify(artifacts).resolve()
        verifyNoInteractions(retainers, nations, spatial, geography, gameKv, diplomacy, sieges)
    }
}
