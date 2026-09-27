package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImperialRegaliaStateTest {
    private fun seal(
        id: String = "han_transmission_seal",
        claims: List<AuthenticityClaim> = listOf(
            AuthenticityClaim("claim:sun", "han_transmission_seal", 10,
                AuthenticityAssessment.DISPUTED, listOf("regalia.transmission_seal_claim"))
        ),
    ) = RegaliaArtifact(
        id = id, kind = RegaliaKind.STATE_REGALIA, claimedIdentity = "han_transmission_seal",
        ownerGeneralId = 10, ownerNationId = null, custodianGeneralId = 10, cityId = 46,
        authenticityClaims = claims, officeScope = null, custodyHistory = emptyList(),
    )

    @Test
    fun `one physical artifact id cannot appear twice in a world`() {
        assertFailsWith<IllegalArgumentException> {
            ImperialRegaliaState(listOf(seal(), seal()))
        }
        // Two objects may compete for the same claimed identity without either being declared genuine.
        assertEquals(2, ImperialRegaliaState(listOf(seal(), seal("forged_seal"))).artifacts.size)
    }

    @Test
    fun `seizure changes custody but never grants authenticity or an imperial title`() {
        val before = ImperialRegaliaState(listOf(seal()))
        val after = before.transferCustody("han_transmission_seal", "seizure:1",
            RegaliaCustodyEventType.SEIZED, 20, 10, 20, 130, 123L)
        val item = after.artifacts.single()
        assertEquals(20, item.custodianGeneralId)
        assertEquals(130, item.cityId)
        assertEquals(before.artifacts.single().authenticityClaims, item.authenticityClaims)
        assertEquals(10, item.ownerGeneralId)
        assertNull(item.officeScope)
        assertEquals(RegaliaCustodyEventType.SEIZED, item.custodyHistory.single().type)
    }

    @Test
    fun `stale custody and replay request cannot transfer again`() {
        val before = ImperialRegaliaState(listOf(seal()))
        assertFailsWith<IllegalArgumentException> {
            before.transferCustody("han_transmission_seal", "seizure:1",
                RegaliaCustodyEventType.SEIZED, 20, 99, 20, 130, 123L)
        }
        val after = before.transferCustody("han_transmission_seal", "seizure:1",
            RegaliaCustodyEventType.SEIZED, 20, 10, 20, 130, 123L)
        assertFailsWith<IllegalArgumentException> {
            after.transferCustody("han_transmission_seal", "seizure:1",
                RegaliaCustodyEventType.TRANSFERRED, 20, 20, 30, 130, 124L)
        }
        assertFailsWith<IllegalArgumentException> {
            after.transferCustody("han_transmission_seal", "older:event",
                RegaliaCustodyEventType.TRANSFERRED, 20, 20, 30, 130, 122L)
        }
    }

    @Test
    fun `office seal requires matching tenure jurisdiction and active turn`() {
        val scope = OfficeInstrumentScope("seal:1", "tenure:1", "hhs:110:潁川郡", 10, 20)
        assertTrue(scope.isValidFor("tenure:1", "hhs:110:潁川郡", 10))
        assertTrue(scope.isValidFor("tenure:1", "hhs:110:潁川郡", 20))
        assertFalse(scope.isValidFor("tenure:2", "hhs:110:潁川郡", 15))
        assertFalse(scope.isValidFor("tenure:1", "hhs:109:河南尹", 15))
        assertFalse(scope.isValidFor("tenure:1", "hhs:110:潁川郡", 21))
    }

    @Test
    fun `codec distinguishes absent key from damaged state and preserves history`() {
        assertNull(ImperialRegaliaCodec.read(emptyMap()))
        val after = ImperialRegaliaState(listOf(seal())).transferCustody("han_transmission_seal",
            "seizure:1", RegaliaCustodyEventType.SEIZED, 20, 10, 20, 130, 123L)
        val encoded = ImperialRegaliaCodec.write(after)
        assertEquals(after, ImperialRegaliaCodec.read(mapOf(ImperialRegaliaCodec.META_KEY to encoded)))
        assertFailsWith<IllegalArgumentException> {
            ImperialRegaliaCodec.read(mapOf(ImperialRegaliaCodec.META_KEY to
                (encoded + ("schemaVersion" to 2))))
        }
        assertFailsWith<IllegalArgumentException> {
            ImperialRegaliaCodec.read(mapOf(ImperialRegaliaCodec.META_KEY to mapOf("schemaVersion" to 1)))
        }
    }
}
