package opensamguk.gateway.publication.api

import opensamguk.gateway.publication.application.ReadAdminServerPublication
import opensamguk.gateway.publication.application.ChangeServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationState
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminServerPublicationController(private val readPublication: ReadAdminServerPublication, private val changePublication: ChangeServerPublication) {
    @GetMapping("/admin/servers/{serverId}/publication")
    fun read(@PathVariable serverId: String): ResponseEntity<AdminServerPublicationDto> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminServerPublicationDto.from(readPublication.execute(serverId)))

    @PutMapping("/admin/servers/{serverId}/publication")
    fun change(@PathVariable serverId: String, @RequestBody request: ChangeServerPublicationRequest): ResponseEntity<AdminServerPublicationDto> {
        val publication = when (request.state) {
            ServerPublicationState.VERIFYING -> changePublication.verifying(request.verifying(serverId))
            ServerPublicationState.PUBLIC -> changePublication.publish(request.publish(serverId))
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminServerPublicationDto.from(publication))
    }
}
