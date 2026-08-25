package opensamguk.gameapi.member

/**
 * 표시용 회원 정보 — gateway-api의 `users`가 신원 정본이다(OPENSAM-220).
 * game-api는 내부 프로필 계약을 통해 이 projection을 받아 제한된 TTL의 캐시로 소비한다.
 */
data class MemberProfile(
    val name: String,
    val grade: Int,
    val picture: String?,
    val imageServer: Int,
)

fun interface MemberProfileClient {
    fun get(userId: Long): MemberProfile?
}

interface MemberProfileCache {
    fun get(userId: Long): MemberProfile?

    fun put(userId: Long, profile: MemberProfile)
}
