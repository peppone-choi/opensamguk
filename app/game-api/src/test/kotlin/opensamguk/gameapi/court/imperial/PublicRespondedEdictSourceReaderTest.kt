package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.imperial.CourtFactionDecision
import opensamguk.logic.imperial.EdictRecipientDecision
import opensamguk.logic.imperial.EdictReceipt
import opensamguk.logic.imperial.EdictStage
import opensamguk.logic.imperial.EdictViewScope
import opensamguk.logic.imperial.EmperorEdictDecision
import opensamguk.logic.imperial.ImperialEdict
import opensamguk.logic.imperial.ImperialEdictCodec
import opensamguk.logic.imperial.ImperialEdictPipeline
import opensamguk.logic.imperial.ImperialEdictProposal
import opensamguk.logic.imperial.ProjectedImperialEdict
import opensamguk.logic.input.Phase
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class PublicRespondedEdictSourceReaderTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val reader = PublicRespondedEdictSourceReader(worlds)

    private fun proposed(id: String) = ImperialEdict(
        ImperialEdictProposal(id, "han", 11, 12, 13, "proposal body"))

    private fun responded(id: String, secret: Boolean = false): ImperialEdict {
        val reviewed = ImperialEdictPipeline.review(proposed(id),
            if (secret) EmperorEdictDecision.SECRET_ORDER else EmperorEdictDecision.APPROVE,
            CourtFactionDecision.SUPPORT, 12, if (secret) "secret body" else "published body")
        val registered = ImperialEdictPipeline.register(reviewed, 14, "register-$id")
        val sealed = ImperialEdictPipeline.seal(registered, "seal-$id", true)
        val dispatched = ImperialEdictPipeline.dispatch(sealed, 15)
        val delivered = ImperialEdictPipeline.deliver(dispatched, 13)
        return ImperialEdictPipeline.respond(delivered, EdictReceipt(13, EdictRecipientDecision.ACCEPT))
    }

    private fun world(edicts: List<ImperialEdict>) = WorldStateReadEntity(
        id = 1, currentYear = 190, currentMonth = 1, currentPhase = 1,
        meta = mapOf(ImperialEdictCodec.META_KEY to ImperialEdictCodec.write(edicts)))

    @Test
    fun `public source materializes responded edict and omits secret and unpublished records`() {
        `when`(worlds.findProcessWorld()).thenReturn(world(listOf(
            responded("public-response"), responded("secret-response", true), proposed("unpublished"))))
        val source = reader.read()
        assertEquals(PublicRespondedEdictSourceStatus.READY, source.status)
        assertEquals(PublicRespondedEdictContext(1, Phase(190, 1, 1)), source.context)
        assertEquals(listOf(ProjectedImperialEdict("public-response", "han", EdictStage.RESPONDED,
            EdictViewScope.FULL, "published body", 13)), source.records)
    }
}
