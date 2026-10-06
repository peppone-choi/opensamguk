package opensamguk.gateway.publication.api

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.gateway.publication.domain.RegisteredPublicServer
import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationState

data class PublicServerDto(
    val id: String,
    val name: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val generation: Int?,
    val gameUrl: String,
) {
    companion object {
        fun from(server: RegisteredPublicServer) = PublicServerDto(
            server.id, server.name, server.generation, "/game/${server.id}",
        )
    }
}

data class ServerAdmissionDto(
    val serverId: String,
    val sourceStatus: String,
    val state: ServerPublicationState,
    val revision: String,
) {
    companion object {
        fun from(publication: ServerPublication) = ServerAdmissionDto(
            publication.serverId, "KNOWN", publication.state, publication.revision.toString(),
        )
    }
}

data class AdminServerPublicationDto(
    val serverId: String,
    val sourceStatus: String,
    val state: ServerPublicationState,
    val revision: String,
    val operationId: String?,
    val expectedGeneration: Int?,
    val expectedScenarioCode: String?,
    val targetFingerprint: String?,
) {
    companion object {
        fun from(publication: ServerPublication) = AdminServerPublicationDto(
            publication.serverId, "KNOWN", publication.state, publication.revision.toString(),
            publication.target?.operationId, publication.target?.expectedGeneration,
            publication.target?.expectedScenarioCode, publication.target?.fingerprint,
        )
    }
}
