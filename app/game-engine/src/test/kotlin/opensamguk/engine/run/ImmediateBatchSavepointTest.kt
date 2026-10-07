package opensamguk.engine.run

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.common.wire.AcceptRaiseInvaderMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.CourtHandler
import opensamguk.engine.city.CityLedgerEntry
import opensamguk.engine.city.CityLedgerStore
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.read.BoardPostRepository
import opensamguk.infra.read.ContactReader
import opensamguk.infra.read.MessageReadRow
import opensamguk.logic.event.EventStore
import opensamguk.logic.input.QueuedDispatch
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Boundary fixtures supplement the real PostgreSQL lease and intermediate-tick regressions. */
class ImmediateBatchSavepointTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z")
    private val worldId = WorldId(1)

    @Test
    fun `failed batch restores court queue and cached ledger with live events and prior recorder effects`() {
        assertRollback(warmCache = true)
    }

    @Test
    fun `failed batch restores a lazy ledger with live events and prior recorder effects`() {
        assertRollback(warmCache = false)
    }

    @Test
    fun `successful batch keeps court execution ledger and live event effects`() {
        val fixture = fixture(warmCache = true)

        val results = fixture.dispatcher.dispatchEnvelopes(listOf(acceptEnvelope()))

        assertEquals(listOf("accept"), results.map { it.first })
        assertEquals(AcceptRaiseInvaderMessageOk(messageId = 77, invaderNationCount = 1), results.single().second)
        assertEquals(listOf("before", "inside"), fixture.court.takeExecutions().map { it.requestId })
        assertEquals(null, QueuedDispatch.read(checkNotNull(fixture.world.getGeneralById(10)).meta))
        assertEquals(CityLedgerEntry(107, 200, 3), fixture.ledger.entry(worldId, 1))
        assertEquals(2, fixture.events.allRows().size)
        assertEquals(2, fixture.recorder.eventInserts().size)
        assertEquals(1, fixture.recorder.cityLedgerUpserts().size)
        assertEquals(1, fixture.jdbc.reads)
    }

    private fun assertRollback(warmCache: Boolean) {
        val fixture = fixture(warmCache)
        val priorEvents = fixture.events.allRows()
        val priorInserts = fixture.recorder.eventInserts()
        val priorGeneralUpdates = fixture.recorder.generalPatches()
        val deletion = TurnDaemonCommand.DeleteMessage(requestId = "delete", generalId = 10, msgID = 78)

        val thrown = assertFailsWith<DataAccessResourceFailureException> {
            fixture.dispatcher.dispatchEnvelopes(listOf(acceptEnvelope(),
                TurnDaemonCommandEnvelope("delete", now.toString(), deletion)))
        }

        assertSame(fixture.failure, thrown, "original read failure must reach the recovery gate")
        assertEquals("inside", QueuedDispatch.read(checkNotNull(fixture.world.getGeneralById(10)).meta)?.requestId)
        assertEquals(listOf("before"), fixture.court.takeExecutions().map { it.requestId })
        assertEquals(priorEvents, fixture.events.allRows())
        assertEquals(priorInserts, fixture.recorder.eventInserts())
        assertEquals(priorGeneralUpdates, fixture.recorder.generalPatches())
        assertTrue(fixture.recorder.cityLedgerUpserts().isEmpty())
        assertEquals(CityLedgerEntry(100, 200, 3), fixture.ledger.entry(worldId, 1))
        assertEquals(if (warmCache) 1 else 2, fixture.jdbc.reads)
    }

    private fun acceptEnvelope() = TurnDaemonCommandEnvelope("accept", now.toString(),
        TurnDaemonCommand.AcceptRaiseInvaderMessage(requestId = "accept", messageId = 77, generalId = 10))

    private fun fixture(warmCache: Boolean): Fixture {
        val recorder = ChangeRecorder()
        fun queued(id: String) = QueuedDispatch(id, 42, 999, 1)
        val actor = TurnGeneral(id = 10, userId = "42", name = "issuer", nationId = 1, cityId = 1,
            troopId = 0, stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0,
            officerLevel = 12, turnTime = now,
            meta = mapOf(QueuedDispatch.META_KEY to queued("before").toMetaValue()))
        val world = InMemoryTurnWorld(WorldSnapshot(state = TurnWorldState(id = 1, currentYear = 200,
            currentMonth = 1, tickSeconds = 3600, lastTurnTime = now, meta = mapOf("isunited" to 2),
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")),
            generals = listOf(actor), worldId = worldId))
        val court = CourtHandler(world, recorder)
        court.onIssuerTurn(10)
        val current = checkNotNull(world.getGeneralById(10))
        world.applyGeneralDirtyFree(current.copy(meta = current.meta +
            (QueuedDispatch.META_KEY to queued("inside").toMetaValue())))
        val events = EventStore().apply { bindMutationSink(recorder::recordEventMutation) }
        fun insertEvent() = events.insertRaw("month", 0, JsonPrimitive(true), JsonArray(emptyList()))
        insertEvent()
        val jdbc = LedgerReads()
        val ledger = CityLedgerStore(jdbc)
        if (warmCache) ledger.entry(worldId, 1)
        val contacts = mock(ContactReader::class.java)
        val message = MessageReadRow(id = 77, mailbox = 10, type = "private", srcGeneralId = 0,
            srcNationId = 0, destGeneralId = 10, destNationId = 1, time = now,
            validUntil = now.plusSeconds(3600), hasAction = true, deletable = true,
            receiverMessageId = null, text = "raise", srcArray = emptyMap(), destArray = emptyMap(),
            option = mapOf("action" to "raiseInvader", "args" to listOf(1.0, 50.0, 1.0, 1.0)))
        `when`(contacts.findMessage(worldId, 77)).thenReturn(message)
        val failure = DataAccessResourceFailureException("one scoped read failed", SQLException("transient", "08006"))
        `when`(contacts.findMessage(worldId, 78)).thenThrow(failure)
        val dispatcher = TurnDaemonCommandDispatcher(world, recorder, mock(BoardPostRepository::class.java),
            contactReader = contacts, courtHandler = court, cityLedger = ledger, eventStore = events,
            raiseInvader = {
                court.onIssuerTurn(10)
                insertEvent()
                ledger.adjust(worldId, recorder, 1, goldDelta = 7)
                1
            })
        return Fixture(world, recorder, court, events, ledger, jdbc, dispatcher, failure)
    }

    private data class Fixture(
        val world: InMemoryTurnWorld,
        val recorder: ChangeRecorder,
        val court: CourtHandler,
        val events: EventStore,
        val ledger: CityLedgerStore,
        val jdbc: LedgerReads,
        val dispatcher: TurnDaemonCommandDispatcher,
        val failure: DataAccessResourceFailureException,
    )

    private class LedgerReads : NamedParameterJdbcTemplate(mock(DataSource::class.java)) {
        var reads = 0
        override fun query(sql: String, paramSource: SqlParameterSource, rch: RowCallbackHandler) {
            reads++
            val rs = mock(ResultSet::class.java)
            `when`(rs.getInt("city_id")).thenReturn(1)
            `when`(rs.getLong("gold")).thenReturn(100L)
            `when`(rs.getLong("rice")).thenReturn(200L)
            `when`(rs.getInt("garrison")).thenReturn(3)
            rch.processRow(rs)
        }
    }
}
