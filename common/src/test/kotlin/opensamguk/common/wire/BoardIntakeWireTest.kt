package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * F4 Wave C2 (slice C) wire 라운드트립 — board-intake 명령 + result 변형. nullable
 * title/text가 보존되는지(null이 유지되고 ""로 강제 변환되지 않음) 그리고 collapsed [BoardActionResult]가
 * ok와 fail 양쪽에서 라우팅되는지 확인한다.
 */
class BoardIntakeWireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand =
        WireJson.decodeFromString(TurnDaemonCommand.serializer(), WireJson.encodeToString(TurnDaemonCommand.serializer(), c))

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult =
        WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r))

    @Test
    fun `board commands round-trip and preserve nullable title and text`() {
        val article = TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = true, title = "제목", text = "내용")
        assertEquals(article, cmdRoundTrip(article))

        // title/text가 없으면 null로 유지된다(""가 아님) — 핸들러는 null과 blank를 구분한다.
        val blankArticle = TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = false, title = null, text = null)
        val rt = cmdRoundTrip(blankArticle) as TurnDaemonCommand.BoardArticle
        assertEquals(null, rt.title)
        assertEquals(null, rt.text)

        val comment = TurnDaemonCommand.BoardComment(generalId = 10, articleNo = 42, text = "댓글")
        assertEquals(comment, cmdRoundTrip(comment))
    }

    @Test
    fun `board action result collapses on ok and fail`() {
        val ok = BoardActionResult(type = "boardArticle", ok = true, generalId = 10)
        val rok = resRoundTrip(ok)
        assertIs<BoardActionResult>(rok)
        assertEquals(ok, rok)

        val fail = BoardActionResult(type = "boardComment", ok = false, generalId = 10, reason = "게시물이 없습니다.")
        val rfail = resRoundTrip(fail)
        assertIs<BoardActionResult>(rfail)
        assertEquals(fail, rfail)
    }
    @Test
    fun `boardRead result routes to BoardActionResult on ok and fail`() {
        // 회귀: boardRead 가 BOARD_ACTION_TYPES 에 없으면 직렬화기가 throw 해 턴 루프가 멈춘다(2026-09-06 실측).
        val ok = BoardActionResult(type = "boardRead", ok = true, generalId = 10)
        assertEquals(ok, resRoundTrip(ok))
        val fail = BoardActionResult(type = "boardRead", ok = false, generalId = 10, reason = "게시물이 없습니다.")
        assertEquals(fail, resRoundTrip(fail))
    }
}
