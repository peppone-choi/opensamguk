package opensamguk.engine.court.office

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.campaign.CourtHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeAppointmentOffer
import opensamguk.logic.office.OfficeAppointmentRequest
import opensamguk.logic.office.OfficeOfferResponseCommand
import opensamguk.logic.office.OfficeOfferStatus
import opensamguk.logic.world.WorldFormat

class OfficeOfferResponseHandlerTest {
    private val offer = OfficeAppointmentOffer("offer-1",
        OfficeAppointmentRequest(1, 2, "office.commandery-prefect", "hhs-group:109:京兆尹", 7),
        Phase(196, 1, 1), Phase(196, 5, 1), OfficeOfferStatus.PENDING)
    private val meta = mapOf("keep" to "untouched", OfficeAppointmentOffer.META_KEY to offer.toMetaValue())
    private fun actor(id: Int, source: Map<String, Any?>) = TurnGeneral(
        id = id, userId = if (id == 2) "7" else "8", name = "actor-$id", nationId = 1, cityId = 7,
        troopId = 0, stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0,
        officerLevel = 1, turnTime = Instant.EPOCH, gold = 500, rice = 500, meta = source,
    )
    private fun world(source: Map<String, Any?> = meta, native: Boolean = true) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, 196, 1, 3600, Instant.EPOCH, currentPhase = 2,
            config = if (native) mapOf(WorldFormat.CONFIG_KEY to WorldFormat.GENERAL_RETAINER_CAMPAIGN.name) else emptyMap()),
        generals = listOf(actor(1, mapOf("issuer" to "untouched")), actor(2, source)), worldId = WorldId(1),
    ))
    private fun envelope(expected: OfficeAppointmentOffer = offer, accepted: Boolean = true, worldId: WorldId = WorldId(1)) =
        ImmediateInput("request-1", 2, 7, OfficeOfferResponseCommand.INPUT_ID,
            OfficeOfferResponseCommand(worldId, expected, accepted).canonicalArguments())

    private fun expectDenied(world: InMemoryTurnWorld, command: ImmediateInput, code: String) {
        val before = world.listGenerals()
        val state = world.getState()
        var observed: CommandLifecycleResult? = null
        OfficeOfferResponseHandler(world).inputHandler(command) { observed = it }.handle()
        val result = requireNotNull(observed)
        assertFalse(result.ok)
        assertEquals(code, result.code)
        assertEquals("executionRejected", result.type)
        assertEquals("COURT_DECISION", result.commandKind)
        assertEquals(command.generalId, result.generalId)
        assertEquals(before, world.listGenerals())
        assertEquals(state, world.getState())
        assertTrue(world.consumeDirtyState().generals.isEmpty())
    }

    @Test
    fun `valid accept and refusal are denied while cost approval is absent`() {
        for (accepted in listOf(true, false)) {
            val world = world()
            val command = envelope(accepted = accepted)
            expectDenied(world, command, "POLICY_UNAVAILABLE")
            val recorder = ChangeRecorder()
            val before = world.listGenerals()
            val publicResult = CourtHandler(world, recorder).handle(command)
            assertFalse(publicResult.ok)
            assertEquals("NOT_DELIVERED", publicResult.code)
            assertEquals(before, world.listGenerals())
            assertTrue(recorder.generalPatches().isEmpty())
        }
    }

    @Test
    fun `terminal source and deadline refusal do not bypass absent cost approval`() {
        for (status in listOf(OfficeOfferStatus.ACCEPTED, OfficeOfferStatus.REFUSED)) {
            val terminal = offer.copy(status = status)
            expectDenied(world(meta + (OfficeAppointmentOffer.META_KEY to terminal.toMetaValue())),
                envelope(terminal), "POLICY_UNAVAILABLE")
        }
        val late = world()
        late.setCurrentDate(196, 5, 1)
        expectDenied(late, envelope(accepted = false), "POLICY_UNAVAILABLE")
    }

    @Test
    fun `unauthorized owner is rejected before malformed private source or payload`() {
        for (owner in listOf(0, 8)) {
            expectDenied(world(mapOf(OfficeAppointmentOffer.META_KEY to "private corrupt")),
                envelope().copy(ownerUserId = owner, argJson = "malformed"), "FORBIDDEN")
        }
    }

    @Test
    fun `payload world cannot replace actual engine world`() {
        expectDenied(world(), envelope(worldId = WorldId(2)), "WORLD_MISMATCH")
    }

    @Test
    fun `transport actor must be the native candidate`() {
        val foreign = offer.copy(request = offer.request.copy(candidateId = 3))
        expectDenied(world(), envelope(foreign), "FORBIDDEN")
    }

    @Test
    fun `original UI full terms cannot be reconstructed from current source`() {
        val changed = offer.copy(request = offer.request.copy(seatCountyId = 8))
        expectDenied(world(meta + (OfficeAppointmentOffer.META_KEY to changed.toMetaValue())), envelope(), "SOURCE_CHANGED")
        val terminal = offer.copy(status = OfficeOfferStatus.ACCEPTED)
        expectDenied(world(meta + (OfficeAppointmentOffer.META_KEY to terminal.toMetaValue())), envelope(), "SOURCE_CHANGED")
    }

    @Test
    fun `missing and corrupted stored source are not repaired`() {
        for (source in listOf(emptyMap(), mapOf(OfficeAppointmentOffer.META_KEY to null),
            mapOf(OfficeAppointmentOffer.META_KEY to mapOf("version" to 1)))) {
            expectDenied(world(source), envelope(), "STATE_UNAVAILABLE")
        }
    }

    @Test
    fun `invalid payload is rejected without source mutation`() {
        expectDenied(world(), envelope().copy(argJson = "{}"), "INVALID_REQUEST")
    }

    @Test
    fun `invalid channel request identity and absent actor are rejected`() {
        expectDenied(world(), envelope().copy(inputId = "court.other"), "UNKNOWN_INPUT")
        expectDenied(world(), envelope().copy(requestId = "bad request"), "INVALID_REQUEST")
        expectDenied(world(), envelope().copy(generalId = 3), "ACTOR_NOT_FOUND")
        expectDenied(world(native = false), envelope(), "WRONG_RULE_PROFILE")
    }

    @Test
    fun `bad native clock cannot turn cost hold into a response`() {
        val beforeIssue = world()
        beforeIssue.setCurrentDate(195, 12, 3)
        expectDenied(beforeIssue, envelope(), "STATE_UNAVAILABLE")
        val malformed = world()
        malformed.setCurrentDate(196, 0, 1)
        expectDenied(malformed, envelope(), "STATE_UNAVAILABLE")
    }

    @Test
    fun `repeated denied envelope stays denied without claiming durable success replay`() {
        val world = world()
        val request = envelope()
        expectDenied(world, request, "POLICY_UNAVAILABLE")
        expectDenied(world, request, "POLICY_UNAVAILABLE")
    }
}
