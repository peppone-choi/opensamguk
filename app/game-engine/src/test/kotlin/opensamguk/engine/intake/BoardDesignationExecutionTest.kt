package opensamguk.engine.intake

import opensamguk.common.wire.CouncilInput
import opensamguk.common.wire.BoardActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.entity.BoardPostEntity
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.council.*
import opensamguk.logic.input.PoliticalInput
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.actions.intake.BoardActions
import org.mockito.Mockito.*
import java.time.Instant
import kotlin.test.*

/** Production handlers over queued-command fixtures; no Redis, live account or operational DB. */
class BoardDesignationExecutionTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private fun person(id: Int, office: Int) = TurnGeneral(id = id, name = "QA 장수$id",
        userId = if (id == 10) "7" else "8", nationId = 1, cityId = 5, troopId = 0,
        stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0,
        officerLevel = office, npcState = 0, turnTime = at, meta = mapOf("lord" to (id == 10)))

    private inner class Fixture(office: Int, config: Map<String, Any?> = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")) {
        val world = InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, at, config = config),
            generals = listOf(person(10, 12), person(11, office)),
            accessLogs = listOf(GeneralAccessLog(11, userId = 8, lastRefresh = at, refresh = 7)),
            nations = listOf(Nation(1, "QA 세력", "#111111", chiefGeneralId = 10,
                meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-1", PoliticalInput.RISE)))))
        val recorder = ChangeRecorder()
        val posts = mock(BoardPostRepository::class.java)
        val source = CouncilWorldAuthoritySource(world)
        val council = CouncilHandler(world, recorder, posts, source)
        val board = BoardHandler(world, recorder, posts, nowProvider = { at })
        init {
            val secret = BoardPostEntity(1, true, 10, "QA 군주", "QA 기밀", "fixture-secret", id = 40)
            `when`(posts.findByIdAndNationId(40, 1)).thenReturn(secret)
            `when`(posts.findAccessibleCouncilPost(40, 1, true)).thenReturn(secret)
        }
        fun grant() {
            assertTrue(council.handle(CouncilInput("grant-1", 10, 7, 1, CouncilRequestCodec.GRANT_ACCESS,
                CouncilRequestCodec.encode(CouncilRequest.GrantAccess(11, null)), "ruler-1")).ok)
            assertEquals(setOf(10, 11), source.read(1).readers)
        }
        fun revoke() {
            assertTrue(council.handle(CouncilInput("revoke-1", 10, 7, 1, CouncilRequestCodec.REVOKE_ACCESS,
                CouncilRequestCodec.encode(CouncilRequest.RevokeAccess(11, "grant-1")), "ruler-1")).ok)
            assertEquals(setOf(10), source.read(1).readers)
            assertTrue(recorder.boardPostInserts().isEmpty())
            assertTrue(recorder.boardCommentInserts().isEmpty())
            assertTrue(recorder.boardReadInserts().isEmpty())
        }
    }

    @Test fun `unassigned high office cannot write a secret article under the Council contract`() {
        val f = Fixture(5)
        assertEquals(setOf(10), f.source.read(1).writers)
        val result = f.board.handleArticle(TurnDaemonCommand.BoardArticle("article-1", 11, true, "QA", "본문"))
        assertFalse(result.ok, "Unassigned writer: accepted=${result.ok}, post inserts=${f.recorder.boardPostInserts().size}")
        assertTrue(f.recorder.boardPostInserts().isEmpty())
    }

    @Test fun `designated ordinary officer can write a secret article without an office promotion`() {
        val f = Fixture(1)
        f.grant()
        val result = assertIs<BoardActionResult>(f.board.handleArticle(
            TurnDaemonCommand.BoardArticle("article-1", 11, true, "QA", "본문")))
        assertTrue(result.ok, "Designated ordinary writer: reason=${result.reason}")
        assertEquals(1, f.recorder.boardPostInserts().size)
    }

    @Test fun `article queued while designated is refused after designation revocation`() {
        val f = Fixture(5)
        f.grant()
        val queued = TurnDaemonCommand.BoardArticle("article-before-revoke", 11, true, "QA", "본문")
        f.revoke()
        val before = f.world.getState()
        val beforeKv = f.recorder.kvDirty()
        val beforeLog = f.world.getAccessLog(11)
        val result = f.board.handleArticle(queued)
        assertEquals(before, f.world.getState())
        assertEquals(beforeKv, f.recorder.kvDirty())
        assertEquals(beforeLog, f.world.getAccessLog(11))
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
        assertFalse(result.ok, "Revoked writer: accepted=${result.ok}, post inserts=${f.recorder.boardPostInserts().size}")
        assertTrue(f.recorder.boardPostInserts().isEmpty())
    }

    @Test fun `comment queued while designated is refused after designation revocation`() {
        val f = Fixture(5)
        f.grant()
        val queued = TurnDaemonCommand.BoardComment("comment-before-revoke", 11, 40, "댓글")
        f.revoke()
        val before = f.world.getState()
        val beforeKv = f.recorder.kvDirty()
        val beforeLog = f.world.getAccessLog(11)
        val result = f.board.handleComment(queued)
        assertEquals(before, f.world.getState())
        assertEquals(beforeKv, f.recorder.kvDirty())
        assertEquals(beforeLog, f.world.getAccessLog(11))
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
        verify(f.posts).findAccessibleCouncilPost(40, 1, false)
        verify(f.posts, never()).findByIdAndNationId(anyInt(), anyInt())
        assertFalse(result.ok, "Revoked commenter: accepted=${result.ok}, comment inserts=${f.recorder.boardCommentInserts().size}")
        assertTrue(f.recorder.boardCommentInserts().isEmpty())
    }

    @Test fun `read queued while designated is refused after designation revocation`() {
        val f = Fixture(5)
        f.grant()
        val queued = TurnDaemonCommand.BoardRead("read-before-revoke", 11, 40)
        f.revoke()
        val result = f.board.handleRead(queued)
        assertFalse(result.ok, "Revoked reader: accepted=${result.ok}, read inserts=${f.recorder.boardReadInserts().size}")
        assertTrue(f.recorder.boardReadInserts().isEmpty())
    }

    @Test fun `unassigned high office cannot publish a meeting notice`() {
        val f = Fixture(5)
        val result = f.board.handleArticle(TurnDaemonCommand.BoardArticle(
            "notice-1", 11, false, "QA 공지", "본문", kind = "notice"))
        assertFalse(result.ok)
        assertTrue(f.recorder.boardPostInserts().isEmpty())
    }

    @Test fun `designated ordinary officer can publish a meeting notice`() {
        val f = Fixture(1)
        f.grant()
        assertTrue(f.board.handleArticle(TurnDaemonCommand.BoardArticle(
            "notice-1", 11, false, "QA 공지", "본문", kind = "notice")).ok)
        assertEquals("notice", f.recorder.boardPostInserts().single().columns["kind"])
    }

    @Test fun `meeting notice queued while designated is refused after revocation`() {
        val f = Fixture(5)
        f.grant()
        val queued = TurnDaemonCommand.BoardArticle(
            "notice-before-revoke", 11, false, "QA 공지", "본문", kind = "notice")
        f.revoke()
        val before = f.world.getState()
        val beforeKv = f.recorder.kvDirty()
        val beforeLog = f.world.getAccessLog(11)
        assertFalse(f.board.handleArticle(queued).ok)
        assertEquals(before, f.world.getState())
        assertEquals(beforeKv, f.recorder.kvDirty())
        assertEquals(beforeLog, f.world.getAccessLog(11))
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
        assertTrue(f.recorder.boardPostInserts().isEmpty())
    }

    @Test fun `malformed designation ledger rejects protected actions without state changes`() {
        val f = Fixture(5)
        f.world.setGameEnvValue(CouncilDesignationCodec.META_KEY, "malformed")
        val before = f.world.getState()
        assertFalse(f.board.handleArticle(TurnDaemonCommand.BoardArticle("secret", 11, true, "QA", "본문")).ok)
        assertFalse(f.board.handleArticle(TurnDaemonCommand.BoardArticle("notice", 11, false, "QA", "본문", kind = "notice")).ok)
        assertFalse(f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글")).ok)
        assertFalse(f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 40)).ok)
        assertEquals(before, f.world.getState())
        assertTrue(f.recorder.boardPostInserts().isEmpty())
        assertTrue(f.recorder.boardCommentInserts().isEmpty())
        assertTrue(f.recorder.boardReadInserts().isEmpty())
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
        assertTrue(f.recorder.kvDirty().isEmpty())
    }

    @Test fun `designated ordinary officer can comment and record a secret read`() {
        val f = Fixture(1)
        f.grant()
        assertTrue(f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글")).ok)
        assertTrue(f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 40)).ok)
        assertEquals(1, f.recorder.boardCommentInserts().size)
        assertEquals(1, f.recorder.boardReadInserts().size)
    }

    @Test fun `ordinary meeting comment and read remain available with an unavailable ledger`() {
        val f = Fixture(1)
        f.world.setGameEnvValue(CouncilDesignationCodec.META_KEY, "malformed")
        val publicPost = BoardPostEntity(1, false, 10, "QA 군주", "QA 회의", "본문", id = 41)
        `when`(f.posts.findAccessibleCouncilPost(41, 1, false)).thenReturn(publicPost)
        assertTrue(f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 41, "댓글")).ok)
        assertTrue(f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 41)).ok)
        assertEquals(1, f.recorder.boardCommentInserts().size)
        assertTrue(f.recorder.boardReadInserts().isEmpty())
    }

    @Test fun `frozen SAMMO secret and notice gates still use office rank`() {
        val f = Fixture(5, mapOf("ruleProfile" to "SAMMO"))
        assertTrue(f.board.handleArticle(TurnDaemonCommand.BoardArticle("secret", 11, true, "QA", "본문")).ok)
        assertTrue(f.board.handleArticle(TurnDaemonCommand.BoardArticle("notice", 11, false, "QA", "본문", kind = "notice")).ok)
        assertTrue(f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글")).ok)
        assertTrue(f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 40)).ok)
        verify(f.posts, never()).findAccessibleCouncilPost(anyInt(), anyInt(), anyBoolean())
    }

    @Test fun `notice requires noticeWriters even when secret writing is allowed`() {
        val f = Fixture(1)
        val handler = BoardHandler(f.world, f.recorder, f.posts, nowProvider = { at },
            authority = CouncilExecutionAuthoritySource { CouncilExecutionAuthority(
                readers = setOf(10, 11), writers = setOf(10, 11), noticeWriters = setOf(10)) })
        assertFalse(handler.handleArticle(TurnDaemonCommand.BoardArticle(
            "notice", 11, false, "QA", "본문", kind = "notice")).ok)
        assertTrue(f.recorder.boardPostInserts().isEmpty())
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
        assertTrue(handler.handleArticle(TurnDaemonCommand.BoardArticle("secret", 11, true, "QA", "본문")).ok)
    }

    @Test fun `unavailable authority source fails closed with no recorder or world changes`() {
        val f = Fixture(5)
        val before = f.world.getState()
        val handler = BoardHandler(f.world, f.recorder, f.posts, nowProvider = { at },
            authority = CouncilExecutionAuthoritySource { throw IllegalStateException("fixture unavailable") })
        assertFalse(handler.handleArticle(TurnDaemonCommand.BoardArticle("secret", 11, true, "QA", "본문")).ok)
        assertFalse(handler.handleArticle(TurnDaemonCommand.BoardArticle("notice", 11, false, "QA", "본문", kind = "notice")).ok)
        assertFalse(handler.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글")).ok)
        assertFalse(handler.handleRead(TurnDaemonCommand.BoardRead("read", 11, 40)).ok)
        assertEquals(before, f.world.getState())
        assertTrue(f.recorder.boardPostInserts().isEmpty())
        assertTrue(f.recorder.boardCommentInserts().isEmpty())
        assertTrue(f.recorder.boardReadInserts().isEmpty())
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
        assertTrue(f.recorder.kvDirty().isEmpty())
    }

    @Test fun `ruler with durable binding retains access without an office promotion`() {
        val f = Fixture(1)
        f.world.applyGeneralDirtyFree(person(10, 1))
        assertTrue(f.board.handleArticle(TurnDaemonCommand.BoardArticle("secret", 10, true, "QA", "본문")).ok)
        assertTrue(f.board.handleArticle(TurnDaemonCommand.BoardArticle("notice", 10, false, "QA", "본문", kind = "notice")).ok)
    }

    @Test fun `missing durable ruler binding cannot be replaced by chief rank`() {
        val f = Fixture(12)
        f.world.applyNationDirtyFree(f.world.getNationById(1)!!.copy(meta = emptyMap()))
        assertFalse(f.board.handleArticle(TurnDaemonCommand.BoardArticle("secret", 10, true, "QA", "본문")).ok)
        assertFalse(f.board.handleArticle(TurnDaemonCommand.BoardArticle("notice", 11, false, "QA", "본문", kind = "notice")).ok)
        assertTrue(f.recorder.boardPostInserts().isEmpty())
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
    }

    @Test fun `affiliation change after admission invalidates the old designation`() {
        val f = Fixture(5)
        f.grant()
        val queued = TurnDaemonCommand.BoardArticle("before-change", 11, true, "QA", "본문")
        f.world.applyGeneralDirtyFree(f.world.getGeneralById(11)!!.copy(nationId = 2))
        assertFalse(f.board.handleArticle(queued).ok)
        assertTrue(f.recorder.boardPostInserts().isEmpty())
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
    }

    @Test fun `invalid article ids and nationless actors are rejected before bounded repository queries`() {
        val f = Fixture(5)
        assertFalse(f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 0, "댓글")).ok)
        assertFalse(f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 0)).ok)
        f.world.applyGeneralDirtyFree(f.world.getGeneralById(11)!!.copy(nationId = 0))
        assertFalse(f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글")).ok)
        assertFalse(f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 40)).ok)
        verifyNoInteractions(f.posts)
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
    }

    @Test fun `raw permissions remain unchanged and protected decisions match the existing Council handler`() {
        data class Row(val office: Int, val expected: Int, val meta: Map<String, Any?> = emptyMap(),
                       val penalty: Map<String, Any?> = emptyMap())
        val rows = listOf(Row(0, -1), Row(1, 0), Row(2, 1), Row(5, 2),
            Row(1, 3, mapOf("permission" to "auditor")), Row(12, 4),
            Row(5, 0, penalty = mapOf("noChief" to true)),
            Row(5, 1, penalty = mapOf("noTopSecret" to true)))
        for (row in rows) {
            for (designated in listOf(false, true)) {
                val f = Fixture(row.office)
                val actor = f.world.getGeneralById(11)!!.let {
                    it.copy(meta = it.meta + row.meta + ("penalty" to row.penalty))
                }
                f.world.applyGeneralDirtyFree(actor)
                assertEquals(row.expected, SecretPermission.check(PerTurnOverlay.toLogicGeneral(actor)))
                if (designated) f.grant()
                val article = f.board.handleArticle(TurnDaemonCommand.BoardArticle("secret", 11, true, "QA", "본문"))
                val notice = f.board.handleArticle(TurnDaemonCommand.BoardArticle("notice", 11, false, "QA", "본문", kind = "notice"))
                val comment = f.board.handleComment(TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글"))
                val read = f.board.handleRead(TurnDaemonCommand.BoardRead("read", 11, 40))
                val canonicalSecret = f.council.handle(CouncilInput("canonical-secret", 11, 8, 1,
                    CouncilRequestCodec.POST_ARTICLE, CouncilRequestCodec.encode(CouncilRequest.PostArticle(
                        "SECRET", "GENERAL", "QA", "본문", null)), null))
                val canonicalNotice = f.council.handle(CouncilInput("canonical-notice", 11, 8, 1,
                    CouncilRequestCodec.POST_ARTICLE, CouncilRequestCodec.encode(CouncilRequest.PostArticle(
                        "MEETING", "NOTICE", "QA 공지", "본문", null)), null))
                assertEquals(canonicalSecret.ok, article.ok, "permission=${row.expected}, designated=$designated")
                assertEquals(canonicalNotice.ok, notice.ok, "notice permission=${row.expected}, designated=$designated")
                assertEquals(actor, f.world.getGeneralById(11))
                assertEquals(row.expected, SecretPermission.check(PerTurnOverlay.toLogicGeneral(f.world.getGeneralById(11)!!)))
                println("PERMISSION_COMPARISON office=${row.office} permission=${row.expected} penalty=${row.penalty} " +
                    "designated=$designated secret=${article.ok} notice=${notice.ok} comment=${comment.ok} read=${read.ok}")
            }
        }
    }

    @Test fun `protected normalization preserves reviewed wire validation and notice vote distinctions`() {
        val base = TurnDaemonCommand.BoardArticle("input", 11, true, "  QA  ", "  본문  ")
        val commands = listOf(base, base.copy(title = null), base.copy(text = null),
            base.copy(title = " ", text = " "), base.copy(title = "QA", text = " "),
            base.copy(kind = "unknown"), base.copy(kind = "vote"),
            base.copy(kind = "vote", voteId = 17), base.copy(operationId = 9),
            base.copy(kind = "operation"), base.copy(kind = "notice", voteId = 17),
            base.copy(isSecret = false, kind = "notice", operationId = 9))
        for (c in commands) {
            val f = Fixture(1)
            f.grant()
            // This is the reviewed bridge's input/output reference, not an application permission conversion.
            val expected = BoardActions.addArticle(c.isSecret, c.title, c.text, 2, c.kind, c.voteId, c.operationId)
            val actual = assertIs<BoardActionResult>(f.board.handleArticle(c))
            when (expected) {
                is BoardActions.ArticleOutcome.Denied -> {
                    assertFalse(actual.ok)
                    assertEquals(expected.reason, actual.reason)
                    assertTrue(f.recorder.boardPostInserts().isEmpty())
                    assertTrue(f.recorder.accessLogUpserts().isEmpty())
                }
                is BoardActions.ArticleOutcome.Insert -> {
                    assertTrue(actual.ok)
                    val row = f.recorder.boardPostInserts().single().columns
                    assertEquals(expected.title, row["title"])
                    assertEquals(expected.text, row["content_html"])
                    assertEquals(expected.isSecret, row["is_secret"])
                    assertEquals(expected.kind, row["kind"])
                    assertEquals(expected.voteId, row["vote_id"])
                    assertEquals(expected.operationId, row["operation_id"])
                }
            }
        }
    }

    @Test fun `ordinary meeting article remains available without a secret designation`() {
        val f = Fixture(1)
        assertTrue(f.board.handleArticle(TurnDaemonCommand.BoardArticle("meeting-1", 11, false, "QA", "본문")).ok)
        assertEquals(1, f.recorder.boardPostInserts().size)
    }
}
