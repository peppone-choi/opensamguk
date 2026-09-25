package opensamguk.logic.imperial

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImperialEdictTest {
    private val office = CentralOfficeGrant("office.taiwei", 77)
    private val proposal = ImperialEdictProposal("edict-1", "later_han", 50, 1, 8, office)
    private val catalog = setOf(office.officeId)

    private fun reviewed(decision: EmperorEdictDecision = EmperorEdictDecision.APPROVE) =
        ImperialEdictPipeline.review(ImperialEdict(proposal), decision, CourtFactionDecision.OPPOSE, 1, "詔")

    private fun registered() = ImperialEdictPipeline.register(reviewed(), 4, "register-1")

    private fun sealed() = ImperialEdictPipeline.seal(registered(), "seal-1", true)

    private fun delivered() = ImperialEdictPipeline.deliver(ImperialEdictPipeline.dispatch(sealed(), 5), 8)

    @Test
    fun `central office grant requires all administrative stages and explicit recipient acceptance`() {
        assertNull(ImperialEdictPipeline.acceptedCentralGrant(ImperialEdict(proposal), catalog))
        assertNull(ImperialEdictPipeline.acceptedCentralGrant(sealed(), catalog))
        val accepted = ImperialEdictPipeline.respond(
            delivered(), EdictReceipt(8, EdictRecipientDecision.ACCEPT, setOf(office.officeId)),
        )
        assertEquals(office, ImperialEdictPipeline.acceptedCentralGrant(accepted, catalog))
        assertNull(ImperialEdictPipeline.acceptedCentralGrant(accepted, emptySet()))
    }

    @Test
    fun `seal stage cannot be skipped or supplied by an unauthorized artifact`() {
        assertFailsWith<IllegalArgumentException> { ImperialEdictPipeline.dispatch(registered(), 5) }
        assertFailsWith<IllegalArgumentException> { ImperialEdictPipeline.seal(registered(), "seal-1", false) }
        assertFailsWith<IllegalArgumentException> { ImperialEdictPipeline.deliver(sealed(), 8) }
    }

    @Test
    fun `imperial refusal closes the proposal and secret order retains limited visibility marker`() {
        val refused = reviewed(EmperorEdictDecision.REFUSE)
        assertEquals(EdictStage.REFUSED, refused.stage)
        assertFailsWith<IllegalArgumentException> { ImperialEdictPipeline.register(refused, 4, "register-1") }
        val secret = reviewed(EmperorEdictDecision.SECRET_ORDER)
        assertTrue(secret.secret)
        assertEquals(EdictStage.REVIEWED, secret.stage)
    }

    @Test
    fun `partial acceptance of an office is explicit and delay cannot grant it`() {
        val partialWithoutOffice = ImperialEdictPipeline.respond(
            delivered(), EdictReceipt(8, EdictRecipientDecision.PARTIAL_ACCEPT),
        )
        assertNull(ImperialEdictPipeline.acceptedCentralGrant(partialWithoutOffice, catalog))
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictPipeline.respond(delivered(), EdictReceipt(8, EdictRecipientDecision.DELAY, setOf(office.officeId)))
        }
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictPipeline.respond(delivered(), EdictReceipt(9, EdictRecipientDecision.ACCEPT, setOf(office.officeId)))
        }
    }

    @Test
    fun `credibility uses confirmed ledger weights and each recipient's political view`() {
        val rules = EdictCredibilityRulesCodec.decode(File("../data/curated/han/imperial-edict-rules.json").readText())
        val evidence = setOf(EdictCredibilityFactor.EMPEROR_ASSENT, EdictCredibilityFactor.AUTHENTIC_SEAL)
        val loyal = EdictRecipientAssessment(8, evidence, audienceModifier = 4, politicalInterestModifier = 3)
        val hostile = EdictRecipientAssessment(9, evidence, audienceModifier = -6, politicalInterestModifier = -5)
        assertEquals(42, EdictCredibility.score(rules, loyal))
        assertEquals(24, EdictCredibility.score(rules, hostile))
        assertTrue(EdictCredibility.score(rules, loyal) > EdictCredibility.score(rules, hostile))
    }
}
