package opensamguk.engine.campaign

import opensamguk.common.wire.*
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.*
import opensamguk.gameapi.precheck.DispatchPrecheckService
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.CourtAdmission
import opensamguk.gameapi.reserve.DomesticAdmission
import opensamguk.infra.persistence.CommandResultRow
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.InputCatalog
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.*

/** GH892 F1: test the shipped producers, not a second implementation of the envelope. */
class InputResolvedReasonContractTest {
    private val catalog = InputCatalog.load()
    private val world = InMemoryTurnWorld(WorldSnapshot(
        worldId = WorldId(1),
        state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, config = mapOf("ruleProfile" to "HWIHA")),
        generals = listOf(TurnGeneral(id = 1, name = "본인", userId = "42", nationId = 1, cityId = 10,
            troopId = 0, stats = GeneralStats(60, 60, 60), experience = 0, dedication = 0,
            officerLevel = 12, npcState = 0, turnTime = Instant.EPOCH)),
        nations = listOf(Nation(1, "본국", "#000")),
    ))
    private val recorder = ChangeRecorder()
    private val court = CourtHandler(world, recorder)
    private val domestic = DomesticHandler(world, recorder, DomesticContext())

    private fun command(inputId: String, owner: Int = 42) =
        TurnDaemonCommand.ImmediateInput("contract-test", 1, owner, inputId, "{}")

    private fun assertFailure(result: CommandLifecycleResult) {
        assertFalse(result.ok)
        val resolved = assertNotNull(result.inputResolved)
        assertFalse(resolved.ok)
        assertEquals(result.code, resolved.reason)
        assertTrue(resolved.reason in assertNotNull(catalog[resolved.inputId]).failureReasons,
            "${resolved.inputId}: ${resolved.reason} is not a registered failure code")
    }

    @Test fun `court producer uses registered codes and preserves human messages`() {
        val invalid = court.handle(command("court.dispatch"))
        assertFailure(invalid)
        assertEquals("INVALID_REQUEST", invalid.code)
        assertEquals("발령 대상과 목적지를 확인해 주세요.", invalid.reason)
        val forbidden = court.handle(command("court.dispatch", 43))
        assertEquals("FORBIDDEN", forbidden.inputResolved?.reason)
        assertEquals("자신의 장수만 조작할 수 있습니다.", forbidden.reason)
        // Existing ledger debts are not silently repaired by this producer-only PR.
        assertEquals("INVALID_INPUT_CHANNEL", court.handle(command("action.enlist")).inputResolved?.reason)
        assertFailure(court.handle(command("stratagem.play")))
    }

    @Test fun `domestic producer uses registered codes and preserves human messages`() {
        for (id in listOf("placement.assign", "policy.set", "work.start")) {
            assertFailure(domestic.handle(command(id)))
            val forbidden = domestic.handle(command(id, 43))
            assertEquals("FORBIDDEN", forbidden.inputResolved?.reason)
            assertEquals("자신의 장수만 조작할 수 있습니다.", forbidden.reason)
        }
        assertEquals("방침 대상과 방침을 확인해 주세요.", domestic.handle(command("policy.set")).reason)
    }

    @Test fun `court and domestic API admission and engine rejection share the ledger reason set`() {
        val courtDenied = assertFailsWith<AdmissionDenied> {
            CourtAdmission(mock(DispatchPrecheckService::class.java))
                .canonicalArguments(1, 42, "court.dispatch", "{}")
        }
        val domesticDenied = assertFailsWith<AdmissionDenied> {
            DomesticAdmission(mock(DomesticReader::class.java))
                .canonicalArguments(1, 42, "policy.set", "{}")
        }
        for ((id, denial, execution) in listOf(
            Triple("court.dispatch", courtDenied, court.handle(command("court.dispatch"))),
            Triple("policy.set", domesticDenied, domestic.handle(command("policy.set"))),
        )) {
            val reasons = assertNotNull(catalog[id]).failureReasons
            assertTrue(denial.code in reasons)
            assertFailure(execution)
            assertEquals(denial.code, execution.inputResolved!!.reason)
            // The personal-turn channel also rejects standing/court inputs with a code in that same set.
            val personalRejection = if (id.startsWith("court.")) court.rejectPersonalReservation(1, id)
                else domestic.rejectPersonalReservation(id)
            assertFailure(turnResult(personalRejection))
        }
    }

    @Test fun `personal turn producer preserves rejection code human message and successful effects`() {
        val rejected = turnResult(TurnOutcome.Rejected("action.enlist", "INVALID_REQUEST", "출사 대상을 확인해 주세요."))
        assertFailure(rejected)
        assertEquals("출사 대상을 확인해 주세요.", rejected.reason)
        val effects = listOf("existing-effect")
        val applied = turnResult(TurnOutcome.Applied("action.enlist", effects))
        assertTrue(applied.ok)
        assertNull(assertNotNull(applied.inputResolved).reason)
        assertEquals(effects, applied.inputResolved!!.effects)
    }

    @Test fun `court and domestic result helpers forward known effects without inventing any`() {
        // These paths have no effect list at intake. Exercise the existing result seams without
        // adding gameplay mutations or synthetic effect names to production handlers.
        for ((handler, id) in listOf(court to "court.dispatch", domestic to "policy.set")) {
            val empty = handlerResult(handler, id, emptyList())
            assertTrue(empty.ok)
            assertNull(assertNotNull(empty.inputResolved).reason)
            assertTrue(empty.inputResolved!!.effects.isEmpty())
            val supplied = listOf("executor-supplied-effect")
            val result = handlerResult(handler, id, supplied)
            assertNull(assertNotNull(result.inputResolved).reason)
            assertEquals(supplied, result.inputResolved!!.effects)
        }
    }

    @Test fun `unknown inputs have no registry envelope in all three producers`() {
        assertNull(court.handle(command("court.missing")).inputResolved)
        assertNull(domestic.handle(command("policy.missing")).inputResolved)
        assertNull(turnResult(TurnOutcome.Rejected("action.missing", "UNKNOWN_INPUT", "등록되지 않은 입력입니다.")).inputResolved)
    }

    @Test fun `red probe rejects the old human text reason and unregistered code in all producers`() {
        for (result in listOf(court.handle(command("court.dispatch")), domestic.handle(command("policy.set")),
            turnResult(TurnOutcome.Rejected("action.enlist", "INVALID_REQUEST", "출사 대상을 확인해 주세요.")))) {
            assertFailure(result)
            val envelope = assertNotNull(result.inputResolved)
            assertFailsWith<AssertionError> {
                assertFailure(result.copy(inputResolved = envelope.copy(reason = result.reason)))
            }
            assertFailsWith<AssertionError> {
                assertFailure(result.copy(code = "NOT_IN_LEDGER", inputResolved = envelope.copy(reason = "NOT_IN_LEDGER")))
            }
        }
    }

    // The plan owns only producer hunks: reflection keeps these private mapping seams private.
    private fun handlerResult(handler: Any, id: String, effects: List<String>): CommandLifecycleResult {
        val method = handler.javaClass.declaredMethods.single { it.name == "result" }
        method.isAccessible = true
        val args = if (handler is DomesticHandler)
            arrayOf(1, id, "POLICY", true, null, null, "reservationAccepted", effects)
        else arrayOf(1, id, true, null, null, "executionApplied", effects)
        return method.invoke(handler, *args) as CommandLifecycleResult
    }

    private fun turnResult(outcome: TurnOutcome): CommandLifecycleResult {
        val handler = mock(ReservedTurnHandler::class.java)
        `when`(handler.recorder).thenReturn(recorder)
        val service = TurnRunService(world, mock(RedisCommandStream::class.java),
            mock(TurnDaemonLifecycle::class.java), handler, mock(JdbcFlushExecutor::class.java),
            mock(RealtimePublisher::class.java))
        val handled = ReservedTurnHandler.HandledTurn(1, null, false, null, emptyList(), emptyMap(),
            requestId = "contract-test", inputOutcome = outcome)
        val method = TurnRunService::class.java.declaredMethods.single { it.name == "toExecutionCommandResultRows" }
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val rows = method.invoke(service, listOf(handled), 7L) as List<CommandResultRow>
        val row = rows.single()
        assertEquals(2, row.resultSeq)
        val envelope = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), row.envelopeJson)
        assertEquals(7L, envelope.committedWorldVersion)
        return (envelope.event as TurnDaemonEvent.CommandResult).result as CommandLifecycleResult
    }
}
