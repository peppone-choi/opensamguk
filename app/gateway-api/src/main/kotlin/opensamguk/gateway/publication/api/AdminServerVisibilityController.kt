package opensamguk.gateway.publication.api

import opensamguk.gateway.publication.application.SetServerVisibility
import opensamguk.gateway.publication.domain.ChangeServerVisibility
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class AdminServerVisibilityController(private val visibility: SetServerVisibility) {
    @PutMapping("/admin/servers/{serverId}/visibility")
    fun change(@PathVariable serverId: String, @RequestBody request: ChangeServerVisibilityRequest): ResponseEntity<AdminServerPublicationDto> {
        val revision = request.expectedRevision.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull()
            ?: throw IllegalArgumentException("invalid visibility revision")
        val result = visibility.execute(ChangeServerVisibility(serverId, requireNotNull(request.publiclyVisible), revision))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminServerPublicationDto.from(result))
    }
}
