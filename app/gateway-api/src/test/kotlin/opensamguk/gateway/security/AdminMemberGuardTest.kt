package opensamguk.gateway.security

import opensamguk.infra.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B0-AUTH self/peer 보호 가드 검증 — legacy `j_set_userlist:162,274`의 0.9.0 단순화 규칙.
 * "ADMIN은 다른 ADMIN/자기 자신을 변경할 수 없다."
 */
class AdminMemberGuardTest {

    private val guard = AdminMemberGuard()

    private fun user(id: Long, role: String) =
        UserEntity(id = id, username = "u$id", password = "x", role = role)

    @Test
    fun `ADMIN can mutate a normal USER`() {
        val target = user(2L, "USER")
        guard.assertMutable(actorId = 1L, actorRole = "ADMIN", target = target)
        assertTrue(guard.isMutable(1L, "ADMIN", target))
    }

    @Test
    fun `ADMIN cannot mutate self`() {
        val self = user(1L, "ADMIN")
        val ex = assertThrows(SelfPeerProtectionException::class.java) {
            guard.assertMutable(actorId = 1L, actorRole = "ADMIN", target = self)
        }
        assertEquals("자기 자신은 변경할 수 없습니다.", ex.message)
        assertFalse(guard.isMutable(1L, "ADMIN", self))
    }

    @Test
    fun `ADMIN cannot mutate another ADMIN`() {
        val peer = user(2L, "ADMIN")
        val ex = assertThrows(SelfPeerProtectionException::class.java) {
            guard.assertMutable(actorId = 1L, actorRole = "ADMIN", target = peer)
        }
        assertEquals("다른 운영자는 변경할 수 없습니다.", ex.message)
        assertFalse(guard.isMutable(1L, "ADMIN", peer))
    }

    @Test
    fun `non-ADMIN actor is rejected defensively`() {
        val target = user(2L, "USER")
        assertThrows(SelfPeerProtectionException::class.java) {
            guard.assertMutable(actorId = 3L, actorRole = "USER", target = target)
        }
        assertFalse(guard.isMutable(3L, "USER", target))
    }
}
