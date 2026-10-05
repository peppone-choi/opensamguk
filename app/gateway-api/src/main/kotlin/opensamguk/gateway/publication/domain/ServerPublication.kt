package opensamguk.gateway.publication.domain

enum class ServerPublicationState { PUBLIC, VERIFYING }

data class ServerPublicationTarget(
    val operationId: String,
    val expectedGeneration: Int,
    val expectedScenarioCode: String,
    val fingerprint: String,
) {
    init {
        require(operationId.matches(Regex("[a-f0-9]{32}")))
        require(expectedGeneration >= 0)
        require(expectedScenarioCode.matches(Regex("[A-Za-z0-9_.:-]+")))
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
    }
}

// Registry identity is not proof of the runtime host or DB world identity.
data class ServerPublication(
    val serverId: String,
    val state: ServerPublicationState,
    val revision: Long,
    val target: ServerPublicationTarget?,
) {
    init {
        require(serverId.matches(Regex("[a-z0-9]{1,48}")))
        require(revision > 0)
        require(state != ServerPublicationState.VERIFYING || target != null)
    }
}

data class RegisteredPublicServer(
    val id: String,
    val name: String,
    val generation: Int?,
) {
    init {
        require(id.matches(Regex("[a-z0-9]{1,48}")))
        require(name.isNotBlank())
        require(generation == null || generation >= 0)
    }
}

interface ServerPublicationRepository {
    // Null means confirmed absent registration. Missing publication data throws.
    fun find(serverId: String): ServerPublication?
    fun listPublicServers(): List<RegisteredPublicServer>
}

class ServerPublicationSourceUnavailable : RuntimeException("server publication source is unavailable")
