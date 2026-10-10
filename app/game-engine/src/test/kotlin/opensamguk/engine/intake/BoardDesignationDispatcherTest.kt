package opensamguk.engine.intake

import opensamguk.common.wire.*
import opensamguk.common.world.WorldId
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.*
import opensamguk.infra.entity.BoardPostEntity
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.council.*
import opensamguk.logic.input.PoliticalInput
import opensamguk.logic.input.RuleProfile
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/** Wire and production dispatcher boundary over synthetic state; no Redis or operational DB. */
class BoardDesignationDispatcherTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private fun person(id: Int, office: Int) = TurnGeneral(id = id, name = "QA 장수$id",
        userId = if (id == 10) "7" else "8", nationId = 1, cityId = 5, troopId = 0,
        stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0,
        officerLevel = office, npcState = 0, turnTime = at, meta = mapOf("lord" to (id == 10)))

    private inner class Fixture(office: Int, frozen: Boolean = false) {
        val world = InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, at, config = if (frozen) emptyMap()
                else mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")),
            generals = listOf(person(10, 12), person(11, office)),
            accessLogs = listOf(GeneralAccessLog(11, userId = 8, lastRefresh = at, refresh = 7)),
            nations = listOf(Nation(1, "QA 세력", "#111111", chiefGeneralId = 10,
                meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-1", PoliticalInput.RISE)))))
        val recorder = ChangeRecorder()
        val posts = mock(BoardPostRepository::class.java)
        val dispatcher = TurnDaemonCommandDispatcher(world, recorder, posts, clock = Clock.fixed(at, ZoneOffset.UTC))
        val grant = CouncilInput("grant-1", 10, 7, 1, CouncilRequestCodec.GRANT_ACCESS,
            CouncilRequestCodec.encode(CouncilRequest.GrantAccess(11, null)), "ruler-1")
        val revoke = CouncilInput("revoke-1", 10, 7, 1, CouncilRequestCodec.REVOKE_ACCESS,
            CouncilRequestCodec.encode(CouncilRequest.RevokeAccess(11, "grant-1")), "ruler-1")
        init {
            val secret = BoardPostEntity(1, true, 10, "QA 군주", "QA 기밀", "fixture-secret", id = 40)
            `when`(posts.findByIdAndNationId(40, 1)).thenReturn(secret)
            `when`(posts.findAccessibleCouncilPost(40, 1, true)).thenReturn(secret)
        }
        fun grant() {
            assertTrue(dispatcher.dispatchEnvelopes(listOf(envelope("grant-1", grant))).single().second.ok)
            assertEquals(setOf(10, 11), CouncilWorldAuthoritySource(world).read(1).writers)
        }
        fun deniedWithoutChanges(envelopes: List<TurnDaemonCommandEnvelope>) {
            val state = world.getState()
            val actor = world.getGeneralById(11)
            val log = world.getAccessLog(11)
            val kv = recorder.kvDirty()
            val replies = dispatcher.dispatchEnvelopes(envelopes)
            assertEquals(envelopes.map { it.requestId }, replies.map { it.first })
            assertEquals(envelopes.map { it.command.type }, replies.map { it.second.type })
            replies.forEach { assertFalse(it.second.ok, "${it.first}: ${it.second}") }
            assertEquals(state, world.getState())
            assertEquals(actor, world.getGeneralById(11))
            assertEquals(log, world.getAccessLog(11))
            assertEquals(kv, recorder.kvDirty())
            assertTrue(recorder.boardPostInserts().isEmpty())
            assertTrue(recorder.boardCommentInserts().isEmpty())
            assertTrue(recorder.boardReadInserts().isEmpty())
            assertTrue(recorder.accessLogUpserts().isEmpty())
        }
    }

    private fun envelope(id: String, command: TurnDaemonCommand): TurnDaemonCommandEnvelope {
        val original = TurnDaemonCommandEnvelope(id, at.toString(), command)
        val decoded = decodeCommandEnvelope(encodeCommandPayload(original))
        assertEquals(original, decoded)
        return decoded
    }

    private fun protectedCommands() = listOf(
        envelope("secret", TurnDaemonCommand.BoardArticle("secret", 11, true, "QA", "본문")),
        envelope("notice", TurnDaemonCommand.BoardArticle("notice", 11, false, "QA 공지", "본문", kind = "notice")),
        envelope("comment", TurnDaemonCommand.BoardComment("comment", 11, 40, "댓글")),
        envelope("read", TurnDaemonCommand.BoardRead("read", 11, 40)),
    )

    @Test fun `wire decoded designated ordinary writer is applied by the real dispatcher without promoting permission`() {
        val f = Fixture(1)
        f.grant()
        val actor = f.world.getGeneralById(11)!!
        val before = SecretPermission.check(PerTurnOverlay.toLogicGeneral(actor))
        assertEquals(0, before)
        val replies = f.dispatcher.dispatchEnvelopes(protectedCommands())
        assertEquals(listOf("secret", "notice", "comment", "read"), replies.map { it.first })
        replies.forEach { assertTrue(it.second.ok, "${it.first}: ${it.second}") }
        assertEquals(listOf("general", "notice"), f.recorder.boardPostInserts().map { it.columns["kind"] })
        assertEquals(listOf(true, false), f.recorder.boardPostInserts().map { it.columns["is_secret"] })
        assertEquals(1, f.recorder.boardCommentInserts().size)
        assertEquals(1, f.recorder.boardReadInserts().size)
        assertEquals(actor, f.world.getGeneralById(11))
        assertEquals(before, SecretPermission.check(PerTurnOverlay.toLogicGeneral(f.world.getGeneralById(11)!!)))
    }

    @Test fun `unassigned high office is rejected through dispatcher without social throttle or env writes`() {
        Fixture(5).deniedWithoutChanges(protectedCommands())
    }

    @Test fun `queued envelopes are reauthorized after dispatcher applies revocation`() {
        val f = Fixture(5)
        f.grant()
        val queued = protectedCommands()
        assertTrue(f.dispatcher.dispatchEnvelopes(listOf(envelope("revoke-1", f.revoke))).single().second.ok)
        assertEquals(setOf(10), CouncilWorldAuthoritySource(f.world).read(1).readers)
        f.deniedWithoutChanges(queued)
        verify(f.posts, times(2)).findAccessibleCouncilPost(40, 1, false)
        verify(f.posts, never()).findByIdAndNationId(anyInt(), anyInt())
    }

    @Test fun `same drained batch observes grant then revoke before queued board commands`() {
        val f = Fixture(5)
        val control = Fixture(5)
        val queued = protectedCommands()
        val controlReplies = control.dispatcher.dispatchEnvelopes(listOf(
            envelope("grant-1", control.grant), envelope("revoke-1", control.revoke)))
        assertTrue(controlReplies.all { it.second.ok })
        val replies = f.dispatcher.dispatchEnvelopes(listOf(
            envelope("grant-1", f.grant), envelope("revoke-1", f.revoke)) + queued)
        assertEquals(listOf("grant-1", "revoke-1") + queued.map { it.requestId }, replies.map { it.first })
        assertTrue(replies.take(2).all { it.second.ok })
        replies.drop(2).forEach { assertFalse(it.second.ok, "${it.first}: ${it.second}") }
        assertEquals(control.world.getState(), f.world.getState())
        assertEquals(control.world.getAccessLog(11), f.world.getAccessLog(11))
        assertEquals(control.recorder.kvDirty(), f.recorder.kvDirty())
        assertTrue(f.recorder.boardPostInserts().isEmpty())
        assertTrue(f.recorder.boardCommentInserts().isEmpty())
        assertTrue(f.recorder.boardReadInserts().isEmpty())
        assertTrue(f.recorder.accessLogUpserts().isEmpty())
    }

    @Test fun `malformed authority cannot be replaced by high rank at dispatcher boundary`() {
        val f = Fixture(5)
        f.world.setGameEnvValue(CouncilDesignationCodec.META_KEY, "malformed")
        f.deniedWithoutChanges(protectedCommands())
    }

    @Test fun `frozen archive rank remains the dispatcher gate without designation`() {
        val f = Fixture(5, frozen = true)
        assertEquals(RuleProfile.fromWorldConfig(null), f.world.ruleProfile)
        val replies = f.dispatcher.dispatchEnvelopes(protectedCommands())
        assertEquals(4, replies.size)
        assertTrue(replies.all { it.second.ok })
        verify(f.posts, never()).findAccessibleCouncilPost(anyInt(), anyInt(), anyBoolean())
    }
}
