package opensamguk.gameapi.owner

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeneralResolverTest {
    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val ownership = GeneralOwnershipClassifier(owners, generals)
    private val resolver = GeneralResolver(ownership, generals, nations)

    @Test
    fun `provisional and legacy candidate owner rows do not resolve before visible daemon activation`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        val candidate = GeneralReadEntity(id = 10, npcState = 2, userId = "7")
        `when`(generals.findById(10)).thenReturn(Optional.of(candidate))

        assertNull(resolver.resolveGeneralId(7L))

        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L))

        assertNull(resolver.resolveGeneralId(7L))
    }

    @Test
    fun `legacy finalized owner row resolves from noncandidate durable state`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L))
        val general = GeneralReadEntity(id = 10, npcState = 0, userId = "7")
        `when`(generals.findByUserId("7")).thenReturn(general)

        assertEquals(10, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `visible possessed general resolves from its authoritative typed body`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        `when`(generals.findByUserId("7")).thenReturn(
            GeneralReadEntity(id = 10, npcState = GeneralPossessionService.POSSESSED_NPC_STATE, userId = "7"),
        )

        assertEquals(10, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `playable typed ownership wins over a nonactivated correlated owner row`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        val nonactivated = GeneralReadEntity(id = 10, npcState = 0, userId = "7")
        `when`(generals.findByUserId("7")).thenReturn(nonactivated)

        assertEquals(10, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `stale released owner does not hide a recreated live general`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-released-10"),
        )
        `when`(generals.findByUserId("7")).thenReturn(
            GeneralReadEntity(id = 11, npcState = 0, userId = "7"),
        )

        assertEquals(11, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `direct general user id is the authoritative ownership source`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findByUserId("7"))
            .thenReturn(GeneralReadEntity(id = 11, npcState = 0, userId = "7"))

        assertEquals(11, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `released state three never resolves as a playable typed owner`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findByUserId("7")).thenReturn(
            GeneralReadEntity(id = 10, npcState = 3, userId = "7"),
        )

        assertNull(resolver.resolveGeneralId(7L))
        verify(generals).findByUserId("7")
    }

    @Test
    fun `legacy owner row for a released state three general is stale rather than live`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L))
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10, npcState = 3, userId = null)))
        `when`(generals.findByUserId("7")).thenReturn(null)

        assertNull(resolver.resolveGeneralId(7L))
    }

    @Test
    fun `missing legacy owner target is stale rather than an owned general`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L))
        `when`(generals.findById(10)).thenReturn(Optional.empty())
        `when`(generals.findByUserId("7")).thenReturn(null)

        assertNull(resolver.resolveGeneralId(7L))
    }
}
