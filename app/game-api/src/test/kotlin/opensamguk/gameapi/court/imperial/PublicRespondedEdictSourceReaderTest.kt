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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class PublicRespondedEdictSourceReaderTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val reader = PublicRespondedEdictSourceReader(worlds)

    private fun proposed(id: String) = ImperialEdict(
        ImperialEdictProposal(id, "han", 11, 12, 13, "proposal body"))

    private fun responded(id: String, secret: Boolean = false) = atStage(id, EdictStage.RESPONDED, secret)

    private fun atStage(id: String, stage: EdictStage, secret: Boolean = false): ImperialEdict {
        if (stage == EdictStage.PROPOSED) return proposed(id)
        if (stage == EdictStage.REFUSED) return ImperialEdictPipeline.review(proposed(id),
            EmperorEdictDecision.REFUSE, CourtFactionDecision.OPPOSE, 12, "refused body")
        val reviewed = ImperialEdictPipeline.review(proposed(id),
            if (secret) EmperorEdictDecision.SECRET_ORDER else EmperorEdictDecision.APPROVE,
            CourtFactionDecision.SUPPORT, 12, if (secret) "secret body" else "published body")
        if (stage == EdictStage.REVIEWED) return reviewed
        val registered = ImperialEdictPipeline.register(reviewed, 14, "register-$id")
        if (stage == EdictStage.REGISTERED) return registered
        val sealed = ImperialEdictPipeline.seal(registered, "seal-$id", true)
        if (stage == EdictStage.SEALED) return sealed
        val dispatched = ImperialEdictPipeline.dispatch(sealed, 15)
        if (stage == EdictStage.DISPATCHED) return dispatched
        val delivered = ImperialEdictPipeline.deliver(dispatched, 13)
        if (stage == EdictStage.DELIVERED) return delivered
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

    private fun assertUnavailable() {
        val source = reader.read()
        assertEquals(PublicRespondedEdictSourceStatus.UNAVAILABLE, source.status)
        assertNull(source.context)
        assertNull(source.records)
    }

    @Test
    fun `all nonresponded public stages are absent rather than partial details`() {
        val edicts = EdictStage.entries.filter { it != EdictStage.RESPONDED }
            .map { atStage("unpublished-${it.name}", it) }
        `when`(worlds.findProcessWorld()).thenReturn(world(edicts))
        val source = reader.read()
        assertEquals(PublicRespondedEdictSourceStatus.READY, source.status)
        assertEquals(emptyList(), source.records)
    }

    @Test
    fun `secret stages never publish their bodies existence or total count`() {
        val edicts = EdictStage.entries.filter { it != EdictStage.PROPOSED && it != EdictStage.REFUSED }
            .map { atStage("hidden-${it.name}", it, true) }
        `when`(worlds.findProcessWorld()).thenReturn(world(edicts))
        val source = reader.read()
        assertEquals(PublicRespondedEdictSourceStatus.READY, source.status)
        assertEquals(emptyList(), source.records)
        val json = ObjectMapper().writeValueAsString(source)
        assertFalse(json.contains("hidden-"))
        assertFalse(json.contains("secret body"))
        assertFalse(json.contains("totalCount"))
    }

    @Test
    fun `published response retains original recipient refusal without creating acceptance`() {
        val public = responded("recipient-refused").copy(
            receipt = EdictReceipt(13, EdictRecipientDecision.REFUSE))
        `when`(worlds.findProcessWorld()).thenReturn(world(listOf(public)))
        val record = reader.read().records!!.single()
        assertEquals(EdictStage.RESPONDED, record.stage)
        assertEquals("published body", record.text)
        // Public completion is not a tenure or a successful command execution.
        assertEquals(13, record.recipientFactionId)
    }

    @Test
    fun `actual codec key absence is not seeded with context and no ready records`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 190, currentMonth = 1, currentPhase = 1))
        val source = reader.read()
        assertEquals(PublicRespondedEdictSourceStatus.NOT_SEEDED, source.status)
        assertEquals(PublicRespondedEdictContext(1, Phase(190, 1, 1)), source.context)
        assertNull(source.records)
    }

    @Test
    fun `valid seeded empty source is ready and not a missing source`() {
        `when`(worlds.findProcessWorld()).thenReturn(world(emptyList()))
        val source = reader.read()
        assertEquals(PublicRespondedEdictSourceStatus.READY, source.status)
        assertEquals(emptyList(), source.records)
    }

    @Test
    fun `missing world and invalid persisted game clock are unavailable`() {
        assertUnavailable()
        for (w in listOf(
            WorldStateReadEntity(id = 0, currentYear = 190, currentMonth = 1),
            WorldStateReadEntity(id = 1, currentYear = 0, currentMonth = 1),
            WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 13),
            WorldStateReadEntity(id = 1, currentYear = 190, currentMonth = 1, currentPhase = 4),
        )) {
            `when`(worlds.findProcessWorld()).thenReturn(w)
            assertUnavailable()
        }
    }

    @Test
    fun `explicit null wrong schema and malformed edict reject the entire source`() {
        for (raw in listOf(null,
            mapOf("schemaVersion" to 2, "edicts" to emptyList<Any>()),
            mapOf("schemaVersion" to 1, "edicts" to listOf(mapOf("id" to "broken"))),
        )) {
            `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
                id = 1, currentYear = 190, currentMonth = 1,
                meta = mapOf(ImperialEdictCodec.META_KEY to raw)))
            assertUnavailable()
        }
    }

    @Test
    fun `malformed hidden record cannot produce a partial public success`() {
        val good = ImperialEdictCodec.write(listOf(responded("public-response")))
        val rawRecords = (good.getValue("edicts") as List<*>) + mapOf("brokenSecret" to true)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(
            id = 1, currentYear = 190, currentMonth = 1,
            meta = mapOf(ImperialEdictCodec.META_KEY to mapOf("schemaVersion" to 1, "edicts" to rawRecords))))
        assertUnavailable()
    }

    @Test
    fun `public result retains values without a mutable world or writable record list`() {
        val w = world(listOf(responded("public-response")))
        `when`(worlds.findProcessWorld()).thenReturn(w)
        val source = reader.read()
        w.currentPhase = 2
        w.meta = emptyMap()
        assertEquals(PublicRespondedEdictContext(1, Phase(190, 1, 1)), source.context)
        val records = source.records!!
        assertEquals("published body", records.single().text)
        assertFailsWith<UnsupportedOperationException> { (records as MutableList<*>).clear() }
    }

    @Test
    fun `world format conflict is unavailable but service failures propagate`() {
        doThrow(ResponseStatusException(HttpStatus.CONFLICT)).`when`(worlds).findProcessWorld()
        assertUnavailable()
        doThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).`when`(worlds).findProcessWorld()
        val cause = assertFailsWith<ResponseStatusException> { reader.read() }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, cause.statusCode)
    }
}
