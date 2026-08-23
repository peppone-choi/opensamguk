package opensamguk.gateway.security

import opensamguk.infra.entity.UserEntity
import org.springframework.stereotype.Component

/**
 * 어드민 회원관리 self/peer 보호 가드 — legacy `j_set_userlist.php:162,274` 패러티의 0.9.0 단순화.
 *
 * frozen historical baseline (ADR-LITE-042; not current product authority): legacy는 GRADE 다단계로
 *   - 대상 grade ≥ 본인 grade면 거부(`:162`)
 *   - set_userlevel param ≥ 본인 grade 거부(`:274`)
 * 를 강제한다. opensamguk 0.9.0은 role 단일 게이트(ADMIN)이므로 다단계로 표현 불가 →
 * **"ADMIN은 다른 ADMIN/자기 자신을 강등·삭제·차단할 수 없다"** 단일 규칙으로 단순화한다(EXECUTION_PLAN §0 결정 ②).
 *
 * B2d(강제탈퇴/암호변경/차단/차단해제/권한변경) 핸들러가 mutating 액션 직전에 [assertMutable]을 호출해야 한다.
 * grade 다단계 복원은 B-AUTH-EXT(1.0.0 멀티운영자) 백로그.
 */
@Component
class AdminMemberGuard {

    /**
     * 행위자([actorId], [actorRole])가 [target]에 대해 mutating 액션을 수행할 수 있는지 검증한다.
     * 위반 시 [SelfPeerProtectionException]을 던진다.
     *
     * - 자기 자신(actorId == target.id) 대상이면 거부.
     * - 대상이 ADMIN이면 거부(동급 ADMIN 보호).
     *
     * 비-ADMIN 행위자는 SecurityConfig의 admin 경로 role 게이트에서 이미 차단되므로 여기 도달하지 않으나,
     * 방어적으로 actorRole 비-ADMIN도 거부한다.
     */
    fun assertMutable(actorId: Long, actorRole: String, target: UserEntity) {
        if (actorRole != "ADMIN") {
            throw SelfPeerProtectionException("권한이 없습니다.")
        }
        if (actorId == target.id) {
            throw SelfPeerProtectionException("자기 자신은 변경할 수 없습니다.")
        }
        if (target.role == "ADMIN") {
            throw SelfPeerProtectionException("다른 운영자는 변경할 수 없습니다.")
        }
    }

    /** [assertMutable]을 boolean으로 — 예외 없이 가부만 필요한 호출부용. */
    fun isMutable(actorId: Long, actorRole: String, target: UserEntity): Boolean =
        actorRole == "ADMIN" && actorId != target.id && target.role != "ADMIN"
}

/** self/peer 보호 위반 — 어드민이 자기 자신/다른 ADMIN을 변경하려 할 때. */
class SelfPeerProtectionException(message: String) : RuntimeException(message)
