package opensamguk.gateway.publication.application

import opensamguk.gateway.publication.domain.ServerPublicationRepository
import org.springframework.stereotype.Service

@Service
class ListPublicServers(private val repository: ServerPublicationRepository) {
    fun execute() = repository.listPublicServers()
}
