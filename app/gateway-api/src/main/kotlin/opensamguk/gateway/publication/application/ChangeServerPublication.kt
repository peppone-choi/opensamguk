package opensamguk.gateway.publication.application

import opensamguk.gateway.publication.domain.PublishServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import opensamguk.gateway.publication.domain.VerifyServerPublication
import org.springframework.stereotype.Service

@Service
class ChangeServerPublication(private val writer: ServerPublicationWriter) {
    fun verifying(command: VerifyServerPublication) = writer.verifying(command)
    fun publish(command: PublishServerPublication) = writer.publish(command)
}
