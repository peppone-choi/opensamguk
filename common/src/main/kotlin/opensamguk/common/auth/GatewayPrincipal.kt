package opensamguk.common.auth

/**
 * 게임 서버가 액세스 토큰에서 실제로 쓰는 것 전부 — 신원과 권한뿐이다.
 *
 * 닉네임·등급·아바타 같은 표시 정보는 바뀌는 값이라 토큰에서 읽지 않는다(발급 시점에 박제된다).
 * 그런 값이 필요하면 `users` 를 읽어라([opensamguk.gameapi.member.toMemberProfile]).
 *
 * 게이트웨이는 아직 표시 클레임을 계속 싣는다([GatewayProfileClaims]) — 구버전 게임 서버가
 * 그 클레임을 요구하기 때문이다. 발급을 끊는 것은 게임 서버를 모두 승격한 뒤의 별도 릴리스다.
 */
data class GatewayPrincipal(
    val userId: Long,
    val role: String,
)
