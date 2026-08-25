package opensamguk.gateway.profile

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class MemberProfileResponse(
    val name: String,
    val grade: Int,
    val picture: String?,
    val imageServer: Int,
)

@RestController
@RequestMapping("/internal/users")
class InternalMemberProfileController(
    private val users: UserRepository,
) {
    @GetMapping("/{id}/profile")
    fun profile(@PathVariable id: Long): ResponseEntity<MemberProfileResponse> =
        users.findById(id)
            .map { ResponseEntity.ok(it.toMemberProfileResponse()) }
            .orElseGet { ResponseEntity.notFound().build() }
}

private fun UserEntity.toMemberProfileResponse(): MemberProfileResponse = MemberProfileResponse(
    name = nickname?.takeIf { it.isNotBlank() } ?: username,
    grade = (grade ?: if (role == "ADMIN") 6 else 1).coerceIn(0, 9),
    picture = picture,
    imageServer = if (imgsvr) 1 else 0,
)
