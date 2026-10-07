package opensamguk.gateway.publication.application

import opensamguk.gateway.publication.domain.ChangeServerVisibility
import opensamguk.gateway.publication.domain.ServerVisibilityWriter
import org.springframework.stereotype.Service

@Service
class SetServerVisibility(private val writer: ServerVisibilityWriter) {
    fun execute(command: ChangeServerVisibility) = writer.change(command)
}
