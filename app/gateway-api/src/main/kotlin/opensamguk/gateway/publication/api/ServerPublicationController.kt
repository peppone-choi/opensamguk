package opensamguk.gateway.publication.api

import opensamguk.gateway.publication.application.ListPublicServers
import opensamguk.gateway.publication.application.ReadServerAdmission
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class ServerPublicationController(
    private val listPublicServers: ListPublicServers,
    private val readServerAdmission: ReadServerAdmission,
) {
    @GetMapping("/servers")
    fun list(): ResponseEntity<List<PublicServerDto>> = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(listPublicServers.execute().map { PublicServerDto.from(it) })

    @GetMapping("/internal/servers/{serverId}/admission")
    fun admission(@PathVariable serverId: String): ResponseEntity<ServerAdmissionDto> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ServerAdmissionDto.from(readServerAdmission.execute(serverId)))
}
