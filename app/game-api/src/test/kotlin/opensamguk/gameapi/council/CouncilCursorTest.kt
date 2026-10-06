package opensamguk.gameapi.council

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class CouncilCursorTest {
    private val scope = CouncilScope(2, 3, 101, CouncilRoom.SECRET, CouncilArticleKind.NOTICE)
    private val position = CouncilPosition(Instant.parse("2026-10-01T18:00:00.123456Z"), 42)

    @Test
    fun `정밀 시각과 동률 글 번호를 보존하고 첫 페이지는 커서가 없다`() {
        assertEquals(position, CouncilCursor.decode(CouncilCursor.encode(scope, position), scope))
        assertNull(CouncilCursor.decode(null, scope))
    }

    @Test
    fun `월드 장수 소속 방 종류 변경으로 커서를 재사용할 수 없다`() {
        val encoded = CouncilCursor.encode(scope, position)
        listOf(scope.copy(worldId = 4), scope.copy(generalId = 102), scope.copy(nationId = 4),
            scope.copy(room = CouncilRoom.MEETING), scope.copy(kind = CouncilArticleKind.GENERAL),
            scope.copy(kind = null)).forEach { changed ->
            assertThrows(InvalidCouncilCursor::class.java) { CouncilCursor.decode(encoded, changed) }
        }
    }

    @Test
    fun `손상된 인코딩 빈값 추가 필드와 비양수 글 번호를 거절한다`() {
        val valid = CouncilCursor.encode(scope, position)
        val invalidId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "1|2|3|101|SECRET|NOTICE|2026-10-01T18:00:00.123456Z|0".toByteArray())
        listOf("", "%%%", "$valid=", invalidId, valid.repeat(4)).forEach { raw ->
            assertThrows(InvalidCouncilCursor::class.java) { CouncilCursor.decode(raw, scope) }
        }
    }
}
