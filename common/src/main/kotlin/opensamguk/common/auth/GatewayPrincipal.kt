package opensamguk.common.auth

/**
 * 게임 서버가 액세스 토큰에서 실제로 쓰는 것 전부 — 신원과 권한뿐이다.
 *
 * 닉네임·등급·아바타 같은 표시 정보는 바뀌는 값이라 토큰에서 읽지 않는다(발급 시점에 박제된다).
 * 그런 값이 필요하면 `users` 를 읽어라([opensamguk.gameapi.member.toMemberProfile]).
 *
 * OPENSAM-220/#483 부로 게이트웨이도 표시 클레임을 더 이상 싣지 않는다 — 모든 게임 서버가
 * `users` 조회 경로(b5145ae9, #481)로 승격된 뒤 발급을 끊었다.
 */
data class GatewayPrincipal(
    val userId: Long,
    val role: String,
)
