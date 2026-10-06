package opensamguk.engine.court.office

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DeltaGenerationSession
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.gameapi.court.offer.OfficeStoredOfferView
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeAppointmentOffer
import opensamguk.logic.office.OfficeAppointmentRequest
import opensamguk.logic.office.OfficeOfferStatus

class OfficeOfferResponseExecutorTest {
    private val offer = OfficeAppointmentOffer(
        "office-offer-1", OfficeAppointmentRequest(1, 2, "office.commandery-prefect", "hhs-group:109:京兆尹", 7),
        Phase(196, 1, 1), Phase(196, 5, 1), OfficeOfferStatus.PENDING,
    )
    private val actorMeta = mapOf("otherKey" to mapOf("keep" to 9), OfficeAppointmentOffer.META_KEY to offer.toMetaValue())

    private fun general(id: Int, meta: Map<String, Any?>) = TurnGeneral(
        id = id, userId = if (id == 2) "7" else "8", name = "general-$id", nationId = 1,
        cityId = 7, troopId = 0, stats = GeneralStats(50, 50, 50), experience = 0, dedication = 0,
        officerLevel = 1, turnTime = Instant.EPOCH, meta = meta,
    )

    private fun world(
        meta: Map<String, Any?> = actorMeta,
        clock: Phase = Phase(196, 1, 2),
    ) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, clock.year, clock.month, 3600, Instant.EPOCH, currentPhase = clock.phase),
        generals = listOf(general(1, mapOf("issuerKey" to "preserved")), general(2, meta)),
        worldId = WorldId(1),
    ))

    private fun respond(world: InMemoryTurnWorld, recorder: ChangeRecorder, expected: OfficeAppointmentOffer = offer,
                        accept: Boolean = true) =
        OfficeOfferResponseExecutor(world, recorder).respond(WorldId(1), 2, 7, expected, accept)

    private fun assertUnchanged(world: InMemoryTurnWorld, recorder: ChangeRecorder, before: List<TurnGeneral>) {
        assertEquals(before, world.listGenerals())
        assertTrue(recorder.generalPatches().isEmpty())
        assertTrue(world.consumeDirtyState().generals.isEmpty())
    }

    @Test
    fun `accept writes only offer delta and existing DTO reads cold recorded source`() {
        val world = world()
        val issuer = world.getGeneralById(1)
        val recorder = ChangeRecorder()
        val accepted = respond(world, recorder)
        assertEquals(offer.copy(status = OfficeOfferStatus.ACCEPTED), accepted)
        assertEquals(accepted, OfficeAppointmentOffer.read(world.getGeneralById(2)!!.meta))
        assertEquals(issuer, world.getGeneralById(1))
        assertEquals(actorMeta["otherKey"], world.getGeneralById(2)!!.meta["otherKey"])
        val patch = recorder.generalPatches().single()
        assertEquals(2, patch.id)
        assertTrue(patch.columns.isEmpty())
        assertEquals(setOf(OfficeAppointmentOffer.META_KEY), patch.meta.keys)
        assertTrue(world.consumeDirtyState().generals.isEmpty())

        val coldMeta = MetaJson.decode(MetaJson.encode(actorMeta + patch.meta))
        val coldWorld = this.world(coldMeta)
        val coldOffer = OfficeAppointmentOffer.read(coldWorld.getGeneralById(2)!!.meta)!!
        assertEquals(accepted, coldOffer)
        val dto = OfficeStoredOfferView.project(coldOffer)
        val originalDto = OfficeStoredOfferView.project(offer)
        assertEquals(originalDto.copy(state = "ACCEPTED"), dto)
        assertEquals(actorMeta["otherKey"], coldMeta["otherKey"])
    }

    @Test
    fun `refuse before deadline records native refusal without changing terms`() {
        val world = world()
        val recorder = ChangeRecorder()
        assertEquals(offer.copy(status = OfficeOfferStatus.REFUSED), respond(world, recorder, accept = false))
        assertEquals(OfficeOfferStatus.REFUSED, OfficeAppointmentOffer.read(world.getGeneralById(2)!!.meta)!!.status)
        assertEquals(1, recorder.generalPatches().size)
    }

    @Test
    fun `real world deadline takes precedence over late refusal`() {
        for (clock in listOf(offer.dueAt, offer.dueAt.plus(1))) {
            val world = world(clock = clock)
            assertEquals(offer.copy(status = OfficeOfferStatus.ACCEPTED), respond(world, ChangeRecorder(), accept = false))
        }
    }

    @Test
    fun `full source mismatch rejects same id with changed terms or clock or state`() {
        val variants = listOf(
            offer.copy(id = "other-offer"),
            offer.copy(request = offer.request.copy(issuerId = 3)),
            offer.copy(request = offer.request.copy(officeId = "office.other")),
            offer.copy(request = offer.request.copy(jurisdictionId = "other-jurisdiction")),
            offer.copy(request = offer.request.copy(seatCountyId = 8)),
            offer.copy(issuedAt = offer.issuedAt.plus(1)),
            offer.copy(dueAt = offer.dueAt.plus(1)),
            offer.copy(status = OfficeOfferStatus.ACCEPTED),
        )
        for (changed in variants) {
            val world = world()
            val before = world.listGenerals()
            val recorder = ChangeRecorder()
            assertFailsWith<IllegalArgumentException> { respond(world, recorder, changed) }
            assertUnchanged(world, recorder, before)
        }
    }

    @Test
    fun `wrong world cannot mutate same numeric actor`() {
        val world = world()
        val before = world.listGenerals()
        val recorder = ChangeRecorder()
        assertFailsWith<IllegalArgumentException> {
            OfficeOfferResponseExecutor(world, recorder).respond(WorldId(2), 2, 7, offer, true)
        }
        assertUnchanged(world, recorder, before)
    }

    @Test
    fun `issuer cannot reply for candidate`() {
        val world = world()
        val before = world.listGenerals()
        val recorder = ChangeRecorder()
        assertFailsWith<IllegalArgumentException> {
            OfficeOfferResponseExecutor(world, recorder).respond(WorldId(1), 1, 8, offer, true)
        }
        assertUnchanged(world, recorder, before)
    }

    @Test
    fun `changed ownership or unauthenticated owner rejects before write`() {
        for (owner in listOf(0, 8)) {
            val world = world()
            val before = world.listGenerals()
            val recorder = ChangeRecorder()
            assertFailsWith<IllegalArgumentException> {
                OfficeOfferResponseExecutor(world, recorder).respond(WorldId(1), 2, owner, offer, true)
            }
            assertUnchanged(world, recorder, before)
        }
    }

    @Test
    fun `missing candidate is not recreated`() {
        val world = world()
        world.removeGeneral(2)
        world.consumeDirtyState()
        val before = world.listGenerals()
        val recorder = ChangeRecorder()
        assertFailsWith<IllegalArgumentException> { respond(world, recorder) }
        assertUnchanged(world, recorder, before)
    }

    @Test
    fun `absent null and malformed source are not seeded or repaired`() {
        val badMeta = listOf(
            mapOf("otherKey" to "preserved"),
            mapOf(OfficeAppointmentOffer.META_KEY to null),
            mapOf(OfficeAppointmentOffer.META_KEY to mapOf("version" to 1)),
        )
        for (meta in badMeta) {
            val world = world(meta)
            val before = world.listGenerals()
            val recorder = ChangeRecorder()
            assertFailsWith<IllegalArgumentException> { respond(world, recorder) }
            assertUnchanged(world, recorder, before)
        }
    }

    @Test
    fun `clock before issuance cannot respond`() {
        val world = world(clock = Phase(195, 12, 3))
        val before = world.listGenerals()
        val recorder = ChangeRecorder()
        assertFailsWith<IllegalArgumentException> { respond(world, recorder) }
        assertUnchanged(world, recorder, before)
    }

    @Test
    fun `invalid runtime clock fails without metadata mutation`() {
        val world = world()
        world.setCurrentDate(196, 0, 1)
        world.consumeDirtyState()
        val before = world.listGenerals()
        val recorder = ChangeRecorder()
        assertFailsWith<IllegalArgumentException> { respond(world, recorder) }
        assertUnchanged(world, recorder, before)
    }

    @Test
    fun `fresh terminal source is a no op`() {
        for (status in listOf(OfficeOfferStatus.ACCEPTED, OfficeOfferStatus.REFUSED)) {
            val terminal = offer.copy(status = status)
            val world = world(actorMeta + (OfficeAppointmentOffer.META_KEY to terminal.toMetaValue()))
            val before = world.listGenerals()
            val recorder = ChangeRecorder()
            assertEquals(terminal, respond(world, recorder, terminal, accept = false))
            assertUnchanged(world, recorder, before)
        }
    }

    @Test
    fun `old pending retry cannot overwrite completed response`() {
        val world = world()
        val recorder = ChangeRecorder()
        respond(world, recorder)
        val before = world.listGenerals()
        val patches = recorder.generalPatches()
        assertFailsWith<IllegalArgumentException> { respond(world, recorder, accept = false) }
        assertEquals(before, world.listGenerals())
        assertEquals(patches, recorder.generalPatches())
    }

    @Test
    fun `prepared generation rejects before live row changes`() {
        val world = world()
        val before = world.listGenerals()
        val session = DeltaGenerationSession()
        val recorder = ChangeRecorder(generationSession = session)
        session.prepare()
        assertFailsWith<IllegalStateException> { respond(world, recorder) }
        assertUnchanged(world, recorder, before)
    }

    @Test
    fun `existing unit checkpoints roll back both world and recorder response`() {
        val world = world()
        val before = world.listGenerals()
        val recorder = ChangeRecorder()
        val worldCheckpoint = world.checkpoint()
        val recorderCheckpoint = recorder.checkpoint()
        respond(world, recorder)
        world.restore(worldCheckpoint)
        recorder.restore(recorderCheckpoint)
        assertUnchanged(world, recorder, before)
    }
}
