package opensamguk.gateway.publication

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.publication.api.AdminServerPublicationDto
import opensamguk.gateway.publication.api.PublicServerDto
import opensamguk.gateway.publication.api.ServerAdmissionDto
import opensamguk.gateway.publication.domain.RegisteredPublicServer
import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationState
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerPublicationDtoTest {
    private val mapper = ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private val target = ServerPublicationTarget("a".repeat(32), 0, "scenario_3190", "b".repeat(64))

    @Test
    fun `public wire preserves zero null and canonical route without reset metadata`() {
        for (generation in listOf(0, null)) {
            val dto = PublicServerDto.from(RegisteredPublicServer("pep", "빼섭", generation))
            assertEquals(generation, dto.generation)
            assertEquals("/game/pep", dto.gameUrl)
            val node = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(dto)
            assertEquals(setOf("id", "name", "generation", "gameUrl"), node.fieldNames().asSequence().toSet())
        }
    }

    @Test
    fun `internal wire exposes only process registry identity known state and decimal revision`() {
        val publication = ServerPublication("pep", ServerPublicationState.VERIFYING, Long.MAX_VALUE, target)
        val node = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(ServerAdmissionDto.from(publication))
        assertEquals(setOf("serverId", "sourceStatus", "state", "revision"), node.fieldNames().asSequence().toSet())
        assertEquals("KNOWN", node["sourceStatus"].asText())
        assertEquals("VERIFYING", node["state"].asText())
        assertEquals(Long.MAX_VALUE.toString(), node["revision"].asText())
        val admin = AdminServerPublicationDto.from(publication)
        assertEquals(0, admin.expectedGeneration)
        assertEquals(target.operationId, admin.operationId)
        assertEquals(null, AdminServerPublicationDto.from(ServerPublication("pep", ServerPublicationState.PUBLIC, 1, null)).operationId)
    }

    @Test
    fun `unknown state is not represented as known public and verifying requires exact target`() {
        assertFailsWith<IllegalArgumentException> { ServerPublicationState.valueOf("UNKNOWN") }
        assertFailsWith<IllegalArgumentException> { ServerPublication("pep", ServerPublicationState.VERIFYING, 1, null) }
        assertFailsWith<IllegalArgumentException> { ServerPublication("pep", ServerPublicationState.PUBLIC, 0, null) }
        assertFailsWith<IllegalArgumentException> { target.copy(expectedGeneration = -1) }
        assertFailsWith<IllegalArgumentException> { target.copy(operationId = "forged") }
        assertFailsWith<IllegalArgumentException> { RegisteredPublicServer("../uni", "빼섭", 0) }
    }
}
