package opensamguk.engine.intake

import opensamguk.common.wire.CouncilInput
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.entity.BoardPostEntity
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.council.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant

class CouncilHandlerTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private fun person(id: Int, owner: String? = null, nation: Int = 1) = TurnGeneral(
        id = id, name = "장수$id", nationId = nation, cityId = 5, troopId = 0,
        stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0,
        officerLevel = 12, npcState = 0, userId = owner, turnTime = at,
    )
    private val actor = person(10, "7")
    private val peer = person(11, "8")
    private val world = InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
        state = TurnWorldState(1, 200, 1, 3600, at, config = mapOf("ruleProfile" to "HWIHA")),
        generals = listOf(actor, peer, person(20, nation = 2)),
        nations = listOf(Nation(1, "본국", "#111111"), Nation(2, "타국", "#222222"))))
    private val recorder = ChangeRecorder()
    private val posts = mock(BoardPostRepository::class.java)
    private var proof = CouncilExecutionAuthority()
    private val handler = CouncilHandler(world, recorder, posts, CouncilExecutionAuthoritySource { proof })
    private fun command(action: String, request: CouncilRequest, receipt: String = "request-1",
                        rulerRevision: String? = null) = CouncilInput(
        receipt, 10, 7, 1, action, CouncilRequestCodec.encode(request), rulerRevision)
    private fun article(secret: Boolean = false, kind: String = "GENERAL") = command(
        CouncilRequestCodec.POST_ARTICLE, CouncilRequest.PostArticle(if (secret) "SECRET" else "MEETING",
            kind, "제목", "<p>본문</p>", null))
    private fun parent(secret: Boolean = false) = BoardPostEntity(1, secret, 11, "동료", "제목", "본문", id = 40)
    private fun noWrites() {
        assertTrue(recorder.boardPostInserts().isEmpty())
        assertTrue(recorder.boardCommentInserts().isEmpty())
        assertTrue(recorder.boardReadInserts().isEmpty())
        assertTrue(recorder.kvDirty().isEmpty())
    }

    @Test fun `실행 전에 소유자 또는 소속이 바뀌면 이미 접수된 글도 거절한다`() {
        listOf(actor.copy(userId = "99"), actor.copy(nationId = 2), actor.copy(nationId = 0),
            actor.copy(npcState = 5)).forEach { changed ->
            world.applyGeneralDirtyFree(changed)
            assertEquals("FORBIDDEN", handler.handle(article()).code)
            noWrites()
        }
        verifyNoInteractions(posts)
    }

    @Test fun `높은 직함만으로 SECRET과 공지를 허용하지 않는다`() {
        assertEquals("SECRET_ACCESS_REQUIRED", handler.handle(article(true)).code)
        assertEquals("NOTICE_ACCESS_REQUIRED", handler.handle(article(kind = "NOTICE")).code)
        noWrites(); verifyNoInteractions(posts)
    }

    @Test fun `일반 회의실 글은 현재 소속과 이름만 저장하고 권한 원천 부재를 위조하지 않는다`() {
        assertTrue(handler.handle(article()).ok)
        val row = recorder.boardPostInserts().single().columns
        assertEquals(1, row["nation_id"]); assertEquals(10, row["author_general_id"])
        assertEquals("장수10", row["author_name"]); assertEquals(false, row["is_secret"])
        assertEquals("general", row["kind"])
        assertTrue(recorder.kvDirty().isEmpty())
    }

    @Test fun `비참여자 댓글은 SQL의 secret 허용을 false로 보내고 legacy 본문 조회를 하지 않는다`() {
        val result = handler.handle(command(CouncilRequestCodec.POST_COMMENT, CouncilRequest.PostComment(40, "댓글")))
        assertEquals("FORBIDDEN", result.code)
        verify(posts).findAccessibleCouncilPost(40, 1, false)
        verify(posts, never()).findByIdAndNationId(anyInt(), anyInt())
        noWrites()
    }

    @Test fun `SECRET 열람 후 권한 회수 다음 실행은 차단하고 기존 열람 의도는 보존한다`() {
        proof = CouncilExecutionAuthority(setOf(10), setOf(10), setOf(10))
        `when`(posts.findAccessibleCouncilPost(40, 1, true)).thenReturn(parent(true))
        val request = command(CouncilRequestCodec.MARK_READ, CouncilRequest.MarkRead(40))
        assertTrue(handler.handle(request).ok)
        proof = CouncilExecutionAuthority()
        assertEquals("FORBIDDEN", handler.handle(request.copy(requestId = "request-2")).code)
        assertEquals(listOf(mapOf("post_id" to 40, "general_id" to 10)), recorder.boardReadInserts().map { it.columns })
        assertTrue(recorder.boardCommentInserts().isEmpty())
        verify(posts).findAccessibleCouncilPost(40, 1, false)
    }

    @Test fun `군주 revision이 달라진 접수와 타국 지정은 저장 없이 거절한다`() {
        proof = CouncilExecutionAuthority(setOf(10), setOf(10), setOf(10), 10, "ruler-current")
        val request = command(CouncilRequestCodec.GRANT_ACCESS, CouncilRequest.GrantAccess(11, null),
            rulerRevision = "ruler-old")
        assertEquals("FORBIDDEN", handler.handle(request).code)
        assertEquals("FORBIDDEN", handler.handle(command(CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequest.GrantAccess(20, null), rulerRevision = "ruler-current")).code)
        noWrites()
    }

    @Test fun `지정과 회수는 실제 영수증 revision과 과거 이력을 같은 game_env write에 남긴다`() {
        proof = CouncilExecutionAuthority(setOf(10), setOf(10), setOf(10), 10, "ruler-current")
        val grant = command(CouncilRequestCodec.GRANT_ACCESS, CouncilRequest.GrantAccess(11, null),
            receipt = "grant-1", rulerRevision = "ruler-current")
        assertTrue(handler.handle(grant).ok)
        val stored = CouncilDesignationCodec.decode(PersistedMetaJson.raw(world.getState().meta[CouncilDesignationCodec.META_KEY]))!!
        assertEquals("grant-1", stored.revision); assertEquals(11, stored.grants.single().targetGeneralId)
        val stale = command(CouncilRequestCodec.REVOKE_ACCESS, CouncilRequest.RevokeAccess(11, "stale"),
            receipt = "revoke-stale", rulerRevision = "ruler-current")
        assertEquals("REVISION_CONFLICT", handler.handle(stale).code)
        assertEquals(stored, CouncilDesignationCodec.decode(PersistedMetaJson.raw(world.getState().meta[CouncilDesignationCodec.META_KEY])))
        assertTrue(handler.handle(stale.copy(requestId = "revoke-1", argJson = CouncilRequestCodec.encode(
            CouncilRequest.RevokeAccess(11, "grant-1")))).ok)
        val revoked = CouncilDesignationCodec.decode(PersistedMetaJson.raw(world.getState().meta[CouncilDesignationCodec.META_KEY]))!!
        assertEquals("revoke-1", revoked.revision)
        assertEquals("grant-1", revoked.grants.single().id)
        assertEquals("revoke-1", revoked.grants.single().revokedByRequestId)
        assertEquals("ruler-current", revoked.grants.single().issuerRevision)
        assertEquals(1, recorder.kvDirty().size)
    }

    @Test fun `다른 세력의 부모 또는 SQL 경계를 어긴 대역 행도 댓글 의도를 남기지 않는다`() {
        `when`(posts.findAccessibleCouncilPost(40, 1, false)).thenReturn(parent().also { it.nationId = 2 })
        assertEquals("FORBIDDEN", handler.handle(command(CouncilRequestCodec.POST_COMMENT,
            CouncilRequest.PostComment(40, "댓글"))).code)
        noWrites()
    }
}
