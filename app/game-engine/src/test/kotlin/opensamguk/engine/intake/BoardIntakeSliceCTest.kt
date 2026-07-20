package opensamguk.engine.intake

import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.BoardPostEntity
import opensamguk.infra.read.BoardPostRepository
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F4 Wave C2 (slice C) 풀-사이클 테스트 — 회의실/기밀실 board intake 명령.
 *
 * 각 테스트는 스펙이 강제하는 데몬 경로를 실행한다:
 *   command → [BoardHandler] (검증 + 권한 게이트 + 글 존재 여부 read) →
 *   [ChangeRecorder] board INSERT 채널 → [DatabaseHooks.toFlushPayload] → flush payload 검증.
 *
 * 권한은 [opensamguk.logic.actions.intake.SecretPermission.check]로 판정: officer_level 12 → 4,
 * >=5 → 2, >1 → 1, 0 → -1. 글 추가는 `permission < 0` (국가)와 `isSecret &&
 * permission < 2` (수뇌부)를 게이트하고, 댓글은 조회한 글에서 `is_secret`를 도출한다.
 */
class BoardIntakeSliceCTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int = 10, nationId: Int = 1, officerLevel: Int = 12) = TurnGeneral(
        id = id, name = "유비", nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = officerLevel, gold = 100, turnTime = t0, role = GeneralRole(),
    )

    private fun world(general: TurnGeneral = general()): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(general),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    /** TurnRunService와 동일하게 flush payload를 구성한다 (recorder + world + drained dirty). */
    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    /** `findByIdAndNationId(id, nationId)`가 [article]을 반환하는(없으면 null) [BoardPostRepository]. */
    private fun boardRepo(article: BoardPostEntity? = null): BoardPostRepository =
        Proxy.newProxyInstance(
            BoardPostRepository::class.java.classLoader,
            arrayOf(BoardPostRepository::class.java),
        ) { _, method, _ ->
            when {
                method.name == "findByIdAndNationId" -> article
                method.returnType == java.util.List::class.java -> emptyList<Any>()
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as BoardPostRepository

    private fun post(id: Int = 5, nationId: Int = 1, isSecret: Boolean = false) =
        BoardPostEntity(
            nationId = nationId, isSecret = isSecret, authorGeneralId = 99, authorName = "관우",
            title = "제목", contentHtml = "내용", id = id,
        )

    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder, article: BoardPostEntity? = null) =
        BoardHandler(world, recorder, boardRepo(article))

    // ── article add ───────────────────────────────────────────────────────────────

    @Test
    fun `회의실 article add records a board_post INSERT and flushes it`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleArticle(
            TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = false, title = " 안건 ", text = " 본문 "),
        )

        assertTrue((res as BoardActionResult).ok)
        val rows = recorder.boardPostInserts()
        assertEquals(1, rows.size)
        val c = rows.single().columns
        assertEquals(1, c["nation_id"])
        assertEquals(false, c["is_secret"])
        assertEquals(10, c["author_general_id"])
        assertEquals("유비", c["author_name"])
        assertEquals("안건", c["title"])  // BoardActions가 trim 처리
        assertEquals("본문", c["content_html"])
        // 풀-사이클: recorder 채널이 flush payload로 수렴한다.
        assertEquals(1, flush(world, recorder).boardPostInserts.size)
    }

    @Test
    fun `기밀실 article add by a 수뇌부 officer records is_secret true`() {
        val world = world(general(officerLevel = 12))
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleArticle(
            TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = true, title = "기밀", text = "내용"),
        )
        assertTrue((res as BoardActionResult).ok)
        assertEquals(true, recorder.boardPostInserts().single().columns["is_secret"])
    }

    @Test
    fun `기밀실 article add by a non-수뇌부 officer is denied`() {
        val world = world(general(officerLevel = 2)) // permission 1 < 2 (수뇌부 미달)
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleArticle(
            TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = true, title = "기밀", text = "내용"),
        )
        assertFalse((res as BoardActionResult).ok)
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", res.reason)
        assertTrue(recorder.boardPostInserts().isEmpty())
    }

    @Test
    fun `article add with a null field is denied (Util getPost null vs blank)`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleArticle(
            TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = false, title = null, text = "본문"),
        )
        assertEquals("올바르지 않은 입력입니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `article add with both fields blank is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleArticle(
            TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = false, title = "  ", text = ""),
        )
        assertEquals("제목과 내용이 둘다 비어있습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `article add by a non-nation general is denied`() {
        val world = world(general(officerLevel = 0)) // permission -1 (무소속)
        val recorder = ChangeRecorder()
        val res = handler(world, recorder).handleArticle(
            TurnDaemonCommand.BoardArticle(generalId = 10, isSecret = false, title = "x", text = "y"),
        )
        assertEquals("국가에 소속되어있지 않습니다.", (res as BoardActionResult).reason)
    }

    // ── comment add ───────────────────────────────────────────────────────────────

    @Test
    fun `comment on an existing 회의실 article records a board_comment INSERT and flushes it`() {
        val world = world(general(officerLevel = 2)) // permission 1 — 비밀 아닌 게시판에는 충분
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, article = post(id = 5, isSecret = false)).handleComment(
            TurnDaemonCommand.BoardComment(generalId = 10, articleNo = 5, text = " 좋은 의견 "),
        )

        assertTrue((res as BoardActionResult).ok)
        val rows = recorder.boardCommentInserts()
        assertEquals(1, rows.size)
        val c = rows.single().columns
        assertEquals(5, c["post_id"])
        assertEquals(1, c["nation_id"])
        assertEquals(false, c["is_secret"])
        assertEquals(10, c["author_general_id"])
        assertEquals("유비", c["author_name"])
        assertEquals("좋은 의견", c["content_text"]) // trim 처리됨
        assertEquals(1, flush(world, recorder).boardCommentInserts.size)
    }

    @Test
    fun `comment derives is_secret from the looked-up article and gates 수뇌부`() {
        val world = world(general(officerLevel = 2)) // permission 1 < 2 → 비밀 게시판 deny
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, article = post(id = 5, isSecret = true)).handleComment(
            TurnDaemonCommand.BoardComment(generalId = 10, articleNo = 5, text = "비밀 의견"),
        )
        assertFalse((res as BoardActionResult).ok)
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", res.reason)
        assertTrue(recorder.boardCommentInserts().isEmpty())
    }

    @Test
    fun `comment on a missing article is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, article = null).handleComment(
            TurnDaemonCommand.BoardComment(generalId = 10, articleNo = 999, text = "의견"),
        )
        assertEquals("게시물이 없습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `comment with a null text is denied before the article read`() {
        val world = world()
        val recorder = ChangeRecorder()
        // 글은 존재하지만, text===null이면 먼저 deny되어야 한다 (PHP order :48-72).
        val res = handler(world, recorder, article = post()).handleComment(
            TurnDaemonCommand.BoardComment(generalId = 10, articleNo = 5, text = null),
        )
        assertEquals("올바르지 않은 입력입니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `comment with a blank text is denied`() {
        val world = world()
        val recorder = ChangeRecorder()
        val res = handler(world, recorder, article = post()).handleComment(
            TurnDaemonCommand.BoardComment(generalId = 10, articleNo = 5, text = "   "),
        )
        assertEquals("내용이 비어있습니다.", (res as BoardActionResult).reason)
    }

    @Test
    fun `comment with a null articleNo is denied before the article read`() {
        val world = world()
        val recorder = ChangeRecorder()
        // articleNo===null이면 text가 유효해도 1차 게이트(PHP :32)로 deny — '게시물이 없습니다.'가 아니다.
        // article을 일부러 존재시켜, 글 read 이전에 articleNo-부재가 먼저 deny됨을 증명한다.
        val res = handler(world, recorder, article = post()).handleComment(
            TurnDaemonCommand.BoardComment(generalId = 10, articleNo = null, text = "유효한 내용"),
        )
        assertEquals("올바르지 않은 입력입니다.", (res as BoardActionResult).reason)
        assertTrue(recorder.boardCommentInserts().isEmpty())
    }
}
