package opensamguk.logic.office

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.common.world.WorldId
import opensamguk.logic.input.Phase

class OfficeOfferResponseCommandTest {
    private val offer = OfficeAppointmentOffer("offer-1",
        OfficeAppointmentRequest(1, 2, "office.commandery-prefect", "hhs-group:109:京兆尹", 7),
        Phase(196, 1, 1), Phase(196, 5, 1), OfficeOfferStatus.PENDING)
    private val command = OfficeOfferResponseCommand(WorldId(1), offer, true)
    private fun root() = Json.parseToJsonElement(command.canonicalArguments()) as JsonObject

    @Test
    fun `canonical reply retains complete source and strict Boolean`() {
        for (accepted in listOf(true, false)) {
            val expected = command.copy(accepted = accepted)
            val decoded = OfficeOfferResponseCommand.parse(expected.canonicalArguments())
            assertEquals(expected, decoded)
            assertEquals(expected.canonicalArguments(), decoded!!.canonicalArguments())
        }
    }

    @Test
    fun `missing extra and aliased top level fields are rejected`() {
        assertNull(OfficeOfferResponseCommand.parse(JsonObject(root() - "accepted").toString()))
        assertNull(OfficeOfferResponseCommand.parse(JsonObject(root() + ("ownerUserId" to JsonPrimitive(7))).toString()))
        assertNull(OfficeOfferResponseCommand.parse(JsonObject((root() - "accepted") + ("accept" to JsonPrimitive(true))).toString()))
    }

    @Test
    fun `world strings fractional nonpositive overflow and Boolean values are rejected`() {
        for (value in listOf("\"1\"", "1.0", "1e0", "0", "-1", "2147483648", "true", "null")) {
            val raw = command.canonicalArguments().replace("\"expectedWorldId\":1", "\"expectedWorldId\":$value")
            assertNull(OfficeOfferResponseCommand.parse(raw), value)
        }
    }

    @Test
    fun `accepted strings numbers null and objects are rejected`() {
        for (value in listOf("\"true\"", "1", "0", "null", "{}", "[]")) {
            val raw = command.canonicalArguments().replace("\"accepted\":true", "\"accepted\":$value")
            assertNull(OfficeOfferResponseCommand.parse(raw), value)
        }
    }

    @Test
    fun `native codec rejects changed schema and coerced source numbers`() {
        val raw = command.canonicalArguments()
        for (bad in listOf(
            raw.replace("\"version\":1", "\"version\":\"1\""),
            raw.replace("\"issuerId\":1", "\"issuerId\":1.0"),
            raw.replace("\"issuerId\":1", "\"issuerId\":1e0"),
            raw.replace("\"version\":1", "\"version\":1e0"),
            raw.replace("\"candidateId\":2", "\"candidateId\":2E0"),
            raw.replace("\"candidateId\":2", "\"candidateId\":2147483648"),
            raw.replace("\"status\":\"PENDING\"", "\"status\":\"EXPIRED\""),
            raw.replace("\"request\":{", "\"request\":{\"kind\":\"OFFICE_NOMINATION\","),
            raw.replace("\"seatCountyId\":7", "\"seatCountyId\":null"),
        )) assertNull(OfficeOfferResponseCommand.parse(bad))
    }

    @Test
    fun `duplicate top nested and escaped keys are not overwritten`() {
        val raw = command.canonicalArguments()
        for (bad in listOf(
            raw.replace("\"accepted\":true", "\"accepted\":true,\"accepted\":false"),
            raw.replace("\"issuerId\":1", "\"issuerId\":1,\"issuerId\":3"),
            raw.replace("\"expectedWorldId\":1", "\"expectedWorldId\":1,\"\\u0065xpectedWorldId\":2"),
        )) assertNull(OfficeOfferResponseCommand.parse(bad))
    }

    @Test
    fun `all native states and quoted braces survive without rebuilding terms`() {
        for (status in OfficeOfferStatus.entries) {
            val source = offer.copy(status = status, request = offer.request.copy(jurisdictionId = "literal { [ \" \\ ] }"))
            val expected = command.copy(expectedOffer = source)
            assertEquals(expected, OfficeOfferResponseCommand.parse(expected.canonicalArguments()))
        }
    }

    @Test
    fun `deep unexpected objects are rejected before recursive JSON parsing`() {
        val deep = "{\"unexpected\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        assertNull(OfficeOfferResponseCommand.parse(deep))
        assertNull(OfficeOfferResponseCommand.parse("[]"))
        assertNull(OfficeOfferResponseCommand.parse(command.canonicalArguments() + " trailing"))
        assertNull(OfficeOfferResponseCommand.parse(null))
    }
}
