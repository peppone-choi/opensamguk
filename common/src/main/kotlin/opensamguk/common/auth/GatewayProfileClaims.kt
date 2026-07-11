package opensamguk.common.auth

data class GatewayProfileClaims(
    val userId: Long,
    val username: String,
    val role: String,
    val nickname: String?,
    val grade: Int,
    val picture: String?,
    val imageServer: Int,
)

object GatewayJwtClaims {
    const val TOKEN_TYPE = "token_type"
    const val ACCESS_TOKEN = "access"
    const val REFRESH_TOKEN = "refresh"
    const val USERNAME = "username"
    const val ROLE = "role"
    const val NICKNAME = "nickname"
    const val GRADE = "grade"
    const val PICTURE = "picture"
    const val IMAGE_SERVER = "imgsvr"
}
