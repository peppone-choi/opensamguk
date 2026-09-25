package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImperialEdictCodecTest {
    private val proposal = ImperialEdictProposal("edict-1", "later_han", 50, 1, 8, "詔",
        CentralOfficeGrant("office.taiwei", 77))

    private fun accepted(): ImperialEdict {
        val reviewed = ImperialEdictPipeline.review(ImperialEdict(proposal), EmperorEdictDecision.APPROVE,
            CourtFactionDecision.SUPPORT, 1, "詔")
        val registered = ImperialEdictPipeline.register(reviewed, 3, "register-1")
        val sealed = ImperialEdictPipeline.seal(registered, "seal-1", true)
        val delivered = ImperialEdictPipeline.deliver(ImperialEdictPipeline.dispatch(sealed, 5), 8)
        return ImperialEdictPipeline.respond(delivered,
            EdictReceipt(8, EdictRecipientDecision.ACCEPT, setOf("office.taiwei")))
    }

    @Test
    fun `cold reload preserves a completed edict and missing state stays absent`() {
        assertNull(ImperialEdictCodec.read(emptyMap()))
        val edict = accepted()
        val encoded = ImperialEdictCodec.write(listOf(edict))
        assertEquals(listOf(edict), ImperialEdictCodec.read(mapOf(ImperialEdictCodec.META_KEY to encoded)))
    }

    @Test
    fun `refusal and secret order remain distinct across reload`() {
        val refused = ImperialEdictPipeline.review(ImperialEdict(proposal.copy(id = "refused")),
            EmperorEdictDecision.REFUSE, CourtFactionDecision.SUPPORT, 1, "不許")
        val secret = ImperialEdictPipeline.review(ImperialEdict(proposal.copy(id = "secret")),
            EmperorEdictDecision.SECRET_ORDER, CourtFactionDecision.OPPOSE, 1, "密詔")
        val encoded = ImperialEdictCodec.write(listOf(secret, refused))
        val reloaded = requireNotNull(ImperialEdictCodec.read(mapOf(ImperialEdictCodec.META_KEY to encoded)))
        assertEquals(listOf(refused, secret), reloaded)
        assertEquals(true, reloaded.last().secret)
    }

    @Test
    fun `inconsistent seal stage and duplicate ids cannot be persisted`() {
        val edict = accepted()
        assertFailsWith<IllegalArgumentException> { ImperialEdictCodec.write(listOf(edict.copy(stage = EdictStage.REGISTERED))) }
        assertFailsWith<IllegalArgumentException> { ImperialEdictCodec.write(listOf(edict, edict)) }
        val encoded = ImperialEdictCodec.write(listOf(edict))
        assertFailsWith<IllegalArgumentException> {
            ImperialEdictCodec.read(mapOf(ImperialEdictCodec.META_KEY to (encoded + ("schemaVersion" to 2))))
        }
    }
}
