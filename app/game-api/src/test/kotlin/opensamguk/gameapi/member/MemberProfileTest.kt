package opensamguk.gameapi.member

import opensamguk.infra.entity.UserEntity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 표시용 회원 정보의 폴백 계약(OPENSAM-220).
 *
 * 이 값들은 예전에 액세스 토큰이 실어 나르며 발급 시점에 박제되던 것들이다. 이제 매 요청 `users` 를
 * 읽으므로, 토큰 검증이 하던 범위 강제와 폴백을 여기서 대신 지켜야 한다.
 */
class MemberProfileTest {
    private fun user(
        username: String = "alice",
        nickname: String? = "앨리스",
        role: String = "USER",
        grade: Int? = null,
        imgsvr: Boolean = false,
    ) = UserEntity(
        id = 1L,
        username = username,
        password = "enc",
        role = role,
        nickname = nickname,
        grade = grade,
        imgsvr = imgsvr,
    )

    @Test
    fun `grade 가 없으면 ADMIN 은 6, 그 외는 1`() {
        assertEquals(1, user(role = "USER").toMemberProfile().grade)
        assertEquals(6, user(role = "ADMIN").toMemberProfile().grade)
    }

    @Test
    fun `명시된 grade 는 그대로 쓰되 0-9 밖은 잘라낸다`() {
        assertEquals(4, user(grade = 4).toMemberProfile().grade)
        // 구 토큰 검증의 `grade in 0..9` 를 대신한다 — DB 컬럼에는 제약이 없다.
        assertEquals(9, user(grade = 42).toMemberProfile().grade)
        assertEquals(0, user(grade = -1).toMemberProfile().grade)
    }

    @Test
    fun `표시 이름은 닉네임이고, 비었으면 아이디로 떨어진다`() {
        assertEquals("앨리스", user().toMemberProfile().name)
        assertEquals("alice", user(nickname = null).toMemberProfile().name)
        assertEquals("alice", user(nickname = "   ").toMemberProfile().name)
    }

    @Test
    fun `imgsvr 는 0 과 1 로 옮겨진다`() {
        assertEquals(1, user(imgsvr = true).toMemberProfile().imageServer)
        assertEquals(0, user(imgsvr = false).toMemberProfile().imageServer)
    }
}
