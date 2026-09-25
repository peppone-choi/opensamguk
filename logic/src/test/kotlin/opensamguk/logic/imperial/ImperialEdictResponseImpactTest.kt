package opensamguk.logic.imperial

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImperialEdictResponseImpactTest {
    private val rules = EdictResponseRules.fromJson(File("../data/curated/han/imperial-edict-response-rules.json").readText())
    private val proposal = ImperialEdictProposal("edict-1", "later_han", 50, 1, 8, "詔",
        CentralOfficeGrant("office.taiwei", 77))

    private fun delivered(): ImperialEdict {
        val reviewed = ImperialEdictPipeline.review(ImperialEdict(proposal), EmperorEdictDecision.APPROVE,
            CourtFactionDecision.SUPPORT, 1, "詔")
        val registered = ImperialEdictPipeline.register(reviewed, 3, "register-1")
        val sealed = ImperialEdictPipeline.seal(registered, "seal-1", true)
        return ImperialEdictPipeline.deliver(ImperialEdictPipeline.dispatch(sealed, 5), 8)
    }

    @Test
    fun `edict ids are safe deterministic event identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            proposal.copy(id = "bad id")
        }
    }

    @Test
    fun `response cost increases from delay to refusal to public denunciation`() {
        val delay = ImperialEdictPipeline.respond(delivered(), EdictReceipt(8, EdictRecipientDecision.DELAY))
        val refusal = ImperialEdictPipeline.respond(delivered(), EdictReceipt(8, EdictRecipientDecision.REFUSE))
        val denunciation = ImperialEdictPipeline.respond(delivered(), EdictReceipt(8, EdictRecipientDecision.DENOUNCE))
        val impacts = listOf(delay, refusal, denunciation).map { ImperialEdictResponseImpact.derive(it, rules) }
        assertEquals(listOf(-2, -8, -15), impacts.map { it.courtFavorDelta })
        assertEquals(listOf(false, false, true), impacts.map { it.publiclyDenounced })
        assertEquals(listOf("edict-response:edict-1"), impacts.map { it.requestId }.distinct())
    }

    @Test
    fun `acceptance has no defiance cost and unanswered edicts have no impact`() {
        val accepted = ImperialEdictPipeline.respond(delivered(),
            EdictReceipt(8, EdictRecipientDecision.ACCEPT, setOf("office.taiwei")))
        assertEquals(0, ImperialEdictResponseImpact.derive(accepted, rules).courtFavorDelta)
        assertFailsWith<IllegalArgumentException> { ImperialEdictResponseImpact.derive(delivered(), rules) }
    }
}
