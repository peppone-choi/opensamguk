package opensamguk.gameapi.member

import opensamguk.infra.entity.UserEntity

/**
 * 표시용 회원 정보 — 액세스 토큰이 아니라 `users` 행에서 읽는다(OPENSAM-220).
 * 토큰에는 신원·권한만 있으므로 닉네임·등급·아바타는 매 요청 DB 가 정본이다.
 */
data class MemberProfile(
    val name: String,
    val grade: Int,
    val picture: String?,
    val imageServer: Int,
)

fun UserEntity.toMemberProfile(): MemberProfile = MemberProfile(
    name = nickname?.takeIf { it.isNotBlank() } ?: username,
    // legacy member.GRADE 미사용(null) 폴백 — ADMIN=6, 그 외 1.
    // 범위 강제는 토큰 검증이 하던 일이었다(구 `grade in 0..9`) — DB 로 옮겼으니 여기서 지킨다.
    grade = (grade ?: if (role == "ADMIN") 6 else 1).coerceIn(0, 9),
    picture = picture,
    imageServer = if (imgsvr) 1 else 0,
)
