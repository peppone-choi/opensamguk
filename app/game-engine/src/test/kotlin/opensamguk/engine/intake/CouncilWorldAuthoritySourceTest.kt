package opensamguk.engine.intake

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.entity.BoardPostEntity
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.council.*
import opensamguk.logic.input.PoliticalInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant

class CouncilWorldAuthoritySourceTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private fun person(id: Int, owner: String?, lord: Boolean) = TurnGeneral(id = id, name = "인물$id",
        userId = owner, nationId = 1, cityId = 5, troopId = 0, stats = GeneralStats(70, 70, 70),
        experience = 0, dedication = 0, officerLevel = if (lord) 12 else 1, npcState = 0, turnTime = at,
        meta = mapOf("lord" to lord))
    private val world = InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
        state = TurnWorldState(1, 200, 1, 3600, at, config = mapOf("ruleProfile" to "HWIHA")),
        generals = listOf(person(10, "7", true), person(11, "8", false), person(12, null, false)),
        nations = listOf(Nation(1, "본국", "#111111", chiefGeneralId = 10,
            meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-1", PoliticalInput.RISE)))))
    private val source = CouncilWorldAuthoritySource(world)
    private val recorder = ChangeRecorder()
    private val posts = mock(BoardPostRepository::class.java)
    private val handler = CouncilHandler(world, recorder, posts, source)
    private fun input(id: String, action: String, request: CouncilRequest, actor: Int = 10, owner: Int = 7,
                      rulerRevision: String? = "ruler-1") = TurnDaemonCommand.CouncilInput(id, actor, owner, 1,
        action, CouncilRequestCodec.encode(request), rulerRevision)

    @Test fun `같은 틱 실제 지정과 회수 다음 실행은 현재 proof를 읽고 옛 열람을 보존한다`() {
        assertEquals(setOf(10), source.read(1).readers)
        assertTrue(handler.handle(input("grant-1", CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequest.GrantAccess(11, null))).ok)
        assertEquals(setOf(10, 11), source.read(1).readers)
        `when`(posts.findAccessibleCouncilPost(40, 1, true)).thenReturn(
            BoardPostEntity(1, true, 10, "군주", "기밀", "본문", id = 40))
        val peerRead = input("read-1", CouncilRequestCodec.MARK_READ, CouncilRequest.MarkRead(40), 11, 8)
        assertTrue(handler.handle(peerRead).ok)
        assertTrue(handler.handle(input("revoke-1", CouncilRequestCodec.REVOKE_ACCESS,
            CouncilRequest.RevokeAccess(11, "grant-1"))).ok)
        assertEquals(setOf(10), source.read(1).readers)
        assertEquals("FORBIDDEN", handler.handle(peerRead.copy(requestId = "read-2")).code)
        assertEquals(1, recorder.boardReadInserts().size)
        val persisted = CouncilDesignationCodec.decode(PersistedMetaJson.raw(world.getState().meta[CouncilDesignationCodec.META_KEY]))!!
        assertEquals("grant-1", persisted.grants.single().id)
        assertEquals("revoke-1", persisted.grants.single().revokedByRequestId)
        verify(posts).findAccessibleCouncilPost(40, 1, false)
    }

    @Test fun `현재 chief와 durable binding이 어긋나면 직함이 남아도 모든 SECRET 권한을 닫는다`() {
        val nation = world.getNationById(1)!!
        world.applyNationDirtyFree(nation.copy(chiefGeneralId = 11))
        assertTrue(source.read(1).readers.isEmpty())
        assertEquals("FORBIDDEN", handler.handle(input("grant-1", CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequest.GrantAccess(11, null))).code)
        assertTrue(recorder.kvDirty().isEmpty())
    }

    @Test fun `군주 변경 뒤 이전 지정은 보존하고 미정 정책을 상속으로 계산하지 않는다`() {
        assertTrue(handler.handle(input("grant-1", CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequest.GrantAccess(12, null))).ok)
        val nation = world.getNationById(1)!!
        val next = world.getGeneralById(11)!!
        world.applyGeneralDirtyFree(next.copy(meta = next.meta + ("lord" to true)))
        world.applyNationDirtyFree(nation.copy(chiefGeneralId = 11,
            meta = CurrentRulerBinding.with(nation.meta, 11, "ruler-2", PoliticalInput.ABDICATE)))
        assertEquals(setOf(11), source.read(1).readers)
        assertEquals("FORBIDDEN", handler.handle(input("grant-old", CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequest.GrantAccess(11, "grant-1"))).code)
        val history = CouncilDesignationCodec.decode(PersistedMetaJson.raw(world.getState().meta[CouncilDesignationCodec.META_KEY]))!!
        assertEquals(12, history.grants.single().targetGeneralId)
        assertNull(history.grants.single().revokedByRequestId)
        // Automatic designation inheritance is undecided; an explicit new-ruler designation uses a separate receipt.
        assertTrue(handler.handle(input("grant-new", CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequest.GrantAccess(12, "grant-1"), 11, 8, "ruler-2")).ok)
        assertEquals(setOf(11, 12), source.read(1).readers)
        val updated = CouncilDesignationCodec.decode(PersistedMetaJson.raw(world.getState().meta[CouncilDesignationCodec.META_KEY]))!!
        assertEquals(2, updated.grants.size)
        assertEquals("grant-new", updated.grants.first { it.id == "grant-1" }.revokedByRequestId)
        assertEquals("ruler-2", updated.grants.first { it.id == "grant-new" }.issuerRevision)
    }
}
