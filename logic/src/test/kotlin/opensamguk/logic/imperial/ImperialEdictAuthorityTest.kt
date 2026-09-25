package opensamguk.logic.imperial

import java.io.File
import opensamguk.logic.office.OfficeClaimOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImperialEdictAuthorityTest {
    private val office = CentralOfficeGrant("office.taiwei", 77)
    private val proposal = ImperialEdictProposal("edict-1", "later_han", 50, 1, 8, "詔", office)
    private val house = ImperialHouse("later_han", "後漢", ImperialLineStatus.ACTIVE, 1, null, emptyList(), null, 10, 46, 70)
    private val world = ImperialWorldState(listOf(house), emptyList(), emptyList())
    private val seal = RegaliaArtifact(
        id = "seal-1", kind = RegaliaKind.STATE_REGALIA, claimedIdentity = "imperial-seal",
        ownerGeneralId = null, ownerNationId = null, custodianGeneralId = 4, cityId = 46,
        authenticityClaims = listOf(AuthenticityClaim("claim-1", "imperial-seal", 1, AuthenticityAssessment.CORROBORATED, listOf("witness-1"))),
        officeScope = null, custodyHistory = emptyList(),
    )
    private val regalia = ImperialRegaliaState(listOf(seal))
    private val catalog = CentralOfficeCatalog.decode(File("../data/curated/han/imperial-central-offices.json").readText())

    private fun registered() = ImperialEdictPipeline.register(
        ImperialEdictPipeline.review(ImperialEdict(proposal), EmperorEdictDecision.APPROVE, CourtFactionDecision.SUPPORT, 1, "詔"),
        registrarId = 3, registerId = "register-1",
    )

    @Test
    fun `corroborated seal at the active court allows a central grant proof after delivery and acceptance`() {
        val sealed = ImperialEdictAuthority.sealWithRegalia(registered(), world, regalia, "seal-1", 4, setOf(4))
        val delivered = ImperialEdictPipeline.deliver(ImperialEdictPipeline.dispatch(sealed, 5), 8)
        val accepted = ImperialEdictPipeline.respond(delivered, EdictReceipt(8, EdictRecipientDecision.ACCEPT, setOf(office.officeId)))
        val proof = ImperialEdictAuthority.centralConfirmationProof(accepted, catalog, courtPolityId = 10)
        assertEquals(CourtConfirmationProof("edict-1", office.officeId, 77, 10), proof)
        val prior = OfficeClaims.selfStyle("self", office.officeId, 77)
        val history = OfficeClaims.confirm(listOf(prior), prior.id, "confirmed", 1, requireNotNull(proof))
        assertEquals(OfficeClaimOrigin.SELF_STYLED, history.first().origin)
        assertEquals(OfficeClaimOrigin.COURT_CONFIRMED, history.last().origin)
        assertEquals(ClaimRecognition.RECOGNIZED, history.last().recognitionByPolity[10])
    }

    @Test
    fun `seizing a seal changes custody but does not authorize an edict`() {
        val seized = regalia.transferCustody("seal-1", "seizure-1", RegaliaCustodyEventType.SEIZED, 8, 4, 8, 130, 2)
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictAuthority.sealWithRegalia(registered(), world, seized, "seal-1", 8, setOf(4))
        }
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictAuthority.sealWithRegalia(registered(), world, regalia, "seal-1", 8, setOf(4))
        }
    }

    @Test
    fun `a disputed seal or wrong emperor cannot sign despite possession`() {
        val disputed = ImperialRegaliaState(listOf(seal.copy(authenticityClaims = seal.authenticityClaims.map {
            it.copy(assessment = AuthenticityAssessment.DISPUTED)
        })))
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictAuthority.sealWithRegalia(registered(), world, disputed, "seal-1", 4, setOf(4))
        }
        val otherEmperor = world.copy(houses = listOf(house.copy(holderGeneralId = 2)))
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictAuthority.sealWithRegalia(registered(), otherEmperor, regalia, "seal-1", 4, setOf(4))
        }
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictAuthority.sealWithRegalia(registered(), world, regalia, "seal-1", 4, emptySet())
        }
    }

    @Test
    fun `refused delivery yields no court confirmation proof`() {
        val sealed = ImperialEdictAuthority.sealWithRegalia(registered(), world, regalia, "seal-1", 4, setOf(4))
        val delivered = ImperialEdictPipeline.deliver(ImperialEdictPipeline.dispatch(sealed, 5), 8)
        val refused = ImperialEdictPipeline.respond(delivered, EdictReceipt(8, EdictRecipientDecision.REFUSE))
        assertNull(ImperialEdictAuthority.centralConfirmationProof(refused, catalog, 10))
    }
}
