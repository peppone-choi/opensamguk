package opensamguk.gateway.publication.application

import opensamguk.gateway.publication.domain.ServerPublicationRepository
import org.springframework.stereotype.Service

@Service
class ReadAdminServerPublication(private val repository: ServerPublicationRepository) {
    fun execute(serverId: String) = repository.find(serverId) ?: throw ServerPublicationRegistrationMissing()
}
