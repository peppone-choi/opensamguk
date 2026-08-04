package opensamguk.gameapi.owner

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeneralResolverTest {
    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)

    @Test
    fun `provisional and legacy candidate owner rows do not resolve before visible daemon activation`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        val candidate = GeneralReadEntity(id = 10, npcState = 2, userId = "7")
        `when`(generals.findById(10)).thenReturn(Optional.of(candidate))
        `when`(generals.findByUserId("7")).thenReturn(candidate)

        assertNull(resolver.resolveGeneralId(7L))

        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L))

        assertNull(resolver.resolveGeneralId(7L))
    }

    @Test
    fun `legacy finalized owner row resolves from noncandidate durable state`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L))
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10, npcState = 0)))

        assertEquals(10, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `visible possessed general resolves through its owner row`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, npcState = GeneralPossessionService.POSSESSED_NPC_STATE, userId = "7")),
        )

        assertEquals(10, resolver.resolveGeneralId(7L))
    }

    @Test
    fun `correlated owner cannot fall back to a nonactivated general user id`() {
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        val nonactivated = GeneralReadEntity(id = 10, npcState = 0, userId = "7")
        `when`(generals.findById(10)).thenReturn(Optional.of(nonactivated))
        `when`(generals.findByUserId("7")).thenReturn(nonactivated)

        assertNull(resolver.resolveGeneralId(7L))
    }

    @Test
    fun `direct general user id remains the fallback ownership source`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findByUserId("7")).thenReturn(GeneralReadEntity(id = 11, npcState = 0, userId = "7"))

        assertEquals(11, resolver.resolveGeneralId(7L))
    }
}
