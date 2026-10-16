package opensamguk.engine.court.office

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.CourtStateStore
import opensamguk.engine.flush.DeltaGenerationSession
import opensamguk.engine.turn.*
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.Phase
import opensamguk.logic.office.*
import opensamguk.logic.world.WorldMapVariant

class ProvincialAppointmentExecutorTest {
    internal class Fixture(val worldId: WorldId = WorldId(1)) {
        var offer = OfficeAppointmentOffer("offer-1", OfficeAppointmentRequest(1, 2,
            "office.commandery-prefect", "hhs-group:109:京兆尹", 100),
            Phase(196, 1, 1), Phase(196, 5, 1), OfficeOfferStatus.ACCEPTED)
        var ruler = true
        var owned = setOf(100, 101)
        var jurisdictionId = offer.request.jurisdictionId
        val recorder = ChangeRecorder()
        val world = InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(worldId.value, 196, 1, 3600, Instant.EPOCH, meta = mapOf("keep" to 42)),
            generals = listOf(general(1), general(2)),
            nations = listOf(Nation(7, "N7", "#123456", chiefGeneralId = 1, gold = 731, rice = 947,
                meta = mapOf("keep" to "nation-resource-state"))), worldId = worldId))

        private fun general(id: Int) = TurnGeneral(id = id, userId = "${id + 10}", name = "G$id",
            nationId = 7, cityId = 100, troopId = 0, stats = GeneralStats(50, 50, 50),
            experience = 0, dedication = 0, officerLevel = 1, gold = 113 + id, rice = 227 + id, turnTime = Instant.EPOCH,
            meta = if (id == 2) mapOf(OfficeAppointmentOffer.META_KEY to offer.toMetaValue()) else emptyMap())

        fun sources(active: InMemoryTurnWorld, expected: OfficeAppointmentOffer): OfficeAppointmentContextProjection.Ready {
            val now = active.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
            val binding = OfficeSourceBinding(active.worldId, WorldMapVariant.V3_1133,
                "TEST_ONLY:canonical-join", "a".repeat(64), "b".repeat(64), now)
            val context = OfficeAppointmentContext(expected.request.issuerId, 7, ruler,
                expected.request.candidateId, active.getGeneralById(expected.request.candidateId)!!.nationId,
                true, true, false, OfficeJurisdictionSnapshot(jurisdictionId, setOf(100, 101), 100, 100,
                    owned, emptySet(), emptySet(), emptySet()), emptyList(), emptySet())
            return OfficeAppointmentContextProjection.Ready(context, OfficeSourceReport(binding,
                OfficeSourceKey.entries.toSet(), OfficeSourceKey.entries.toSet(), emptyMap(), emptyMap()))
        }

        fun executor(changes: ChangeRecorder = recorder) = ProvincialAppointmentExecutor(world, changes)

        fun execute(expected: OfficeAppointmentOffer = offer,
                    id: String = "request-1", expectedWorld: WorldId = worldId,
                    changes: ChangeRecorder = recorder) = executor(changes).execute(
            expectedWorld, id, expected, 10, 11, ::sources)

        fun installOffer(updated: OfficeAppointmentOffer) {
            offer = updated
            val actor = world.getGeneralById(2)!!
            world.applyGeneralDirtyFree(actor.copy(meta = actor.meta +
                (OfficeAppointmentOffer.META_KEY to updated.toMetaValue())))
        }

        fun assertNoEffects(block: () -> Unit) {
            val before = world.getState()
            val generals = world.listGenerals()
            val nations = world.listNations()
            val writes = recorder.kvDirty()
            block()
            assertEquals(before, world.getState())
            assertEquals(generals, world.listGenerals())
            assertEquals(nations, world.listNations())
            assertEquals(writes, recorder.kvDirty())
            assertTrue(recorder.generalPatches().isEmpty())
            assertTrue(recorder.nationPatches().isEmpty())
            val dirty = world.consumeDirtyState()
            assertTrue(dirty.generals.isEmpty())
            assertTrue(dirty.nations.isEmpty())
        }
    }

    @Test fun `approved zero cost appoints once with only tenure and receipt writes and no extra decision turn`() {
        val f = Fixture()
        val generals = f.world.listGenerals()
        val nations = f.world.listNations()
        val result = assertIs<ProvincialAppointmentExecutor.Result.Appointed>(f.execute())
        assertFalse(result.replay)
        assertEquals(2, result.tenure.holderId)
        assertEquals(f.offer.request.officeId, result.tenure.officeId)
        assertEquals(f.offer.request.jurisdictionId, result.tenure.jurisdictionId)
        assertEquals(listOf(result.tenure), CourtStateStore(f.world, f.recorder).tenures())
        assertEquals(setOf("localOfficeTenures", "provincialAppointmentReceipts"),
            f.recorder.kvDirty().keys.map { it.key }.toSet())
        assertEquals(42, f.world.getState().meta["keep"])
        // Proposal issuance owns the one ruler decision turn; completion changes neither actor.
        assertEquals(generals, f.world.listGenerals())
        assertEquals(nations, f.world.listNations())
        assertTrue(f.recorder.generalPatches().isEmpty())
        assertTrue(f.recorder.nationPatches().isEmpty())
        val dirty = f.world.consumeDirtyState()
        assertTrue(dirty.generals.isEmpty())
        assertTrue(dirty.nations.isEmpty())
    }

    @Test fun `zhou appointment uses the same native tenure path without claiming physical assumption`() {
        val f = Fixture()
        val provinceOffice = OfficeCatalog.loadClasspath().definitions.first { it.jurisdiction == OfficeJurisdiction.ZHOU }
        f.jurisdictionId = "zhou:test"
        f.installOffer(f.offer.copy(request = f.offer.request.copy(officeId = provinceOffice.id,
            jurisdictionId = f.jurisdictionId)))
        val result = assertIs<ProvincialAppointmentExecutor.Result.Appointed>(f.execute())
        assertEquals(provinceOffice.id, result.tenure.officeId)
        assertEquals("zhou:test", result.tenure.jurisdictionId)
        assertTrue(result.tenure.isActive)
        assertFalse(result.tenure.isAssumed)
        assertNull(result.tenure.seatCountyId)
    }

    @Test fun `unavailable and stale world phase sources never write`() {
        val f = Fixture()
        val fresh = f.sources(f.world, f.offer)
        val variants = listOf<OfficeAppointmentContextProjection>(
            OfficeAppointmentContextProjection.Unavailable(fresh.sources.copy(
                unavailable = mapOf(OfficeSourceKey.CANONICAL_JOIN to OfficeSourceUnavailableReason.MISSING))),
            fresh.copy(sources = fresh.sources.copy(binding = fresh.sources.binding.copy(worldId = WorldId(2)))),
            fresh.copy(sources = fresh.sources.copy(binding = fresh.sources.binding.copy(asOf = Phase(195, 12, 3)))),
            fresh.copy(sources = fresh.sources.copy(knownSections = emptySet())),
        )
        for (projection in variants) f.assertNoEffects {
            assertEquals(ProvincialAppointmentExecutor.Result.SourceUnavailable,
                f.executor().execute(WorldId(1), "request-1", f.offer, 10, 11) { _, _ -> projection })
        }
    }

    @Test fun `rule denials preserve reason and cause no mutation under approved zero cost`() {
        val occupied = OfficeTenure("existing", "office.commandery-prefect", "hhs-group:109:京兆尹",
            3, 1, 7, OfficeClaimOrigin.POLITY_APPOINTMENT, 1, acceptedTurn = 1)
        val maximum = OfficeRules.loadClasspath().maximumConcurrentLocalTenures
        val cases = listOf<Pair<OfficeAppointmentFailure, (Fixture) -> Unit>>(
            OfficeAppointmentFailure.NOT_RULER to { it.ruler = false },
            OfficeAppointmentFailure.CANDIDATE_OUTSIDE_NATION to { f ->
                val actor = f.world.getGeneralById(2)!!
                f.world.applyGeneralDirtyFree(actor.copy(nationId = 8)) },
            OfficeAppointmentFailure.WRONG_JURISDICTION to { it.jurisdictionId = "zhou:other" },
            OfficeAppointmentFailure.SEAT_NOT_OWNED to { it.owned = setOf(101) },
            OfficeAppointmentFailure.OFFICE_OCCUPIED to { f -> CourtStateStore(f.world, ChangeRecorder()).saveTenures(listOf(occupied)) },
            OfficeAppointmentFailure.CONCURRENT_LIMIT to { f -> CourtStateStore(f.world, ChangeRecorder()).saveTenures(
                (1..maximum).map { occupied.copy(id = "prior-$it", holderId = 2, jurisdictionId = "hhs-group:other-$it") }) },
            OfficeAppointmentFailure.COUNTY_OFFICE_USES_PLACEMENT to { f ->
                val county = OfficeCatalog.loadClasspath().definitions.first { it.countyPlacementOnly }
                f.installOffer(f.offer.copy(request = f.offer.request.copy(officeId = county.id))) },
        )
        for ((reason, configure) in cases) {
            val f = Fixture()
            configure(f)
            f.world.consumeDirtyState()
            f.assertNoEffects { assertEquals(ProvincialAppointmentExecutor.Result.Denied(reason), f.execute()) }
        }
    }

    @Test fun `replay is persistent no-op and changed request terms conflict`() {
        val f = Fixture()
        val original = assertIs<ProvincialAppointmentExecutor.Result.Appointed>(f.execute())
        f.world.consumeDirtyState()
        f.assertNoEffects {
            assertEquals(original.copy(replay = true), f.execute())
        }
        f.installOffer(f.offer.copy(request = f.offer.request.copy(seatCountyId = 101)))
        f.assertNoEffects { assertEquals(ProvincialAppointmentExecutor.Result.RequestConflict, f.execute()) }
        assertEquals(1, CourtStateStore(f.world, f.recorder).tenures().size)
    }

    @Test fun `committed replay ignores later captivity changed offer and lost authority`() {
        val f = Fixture()
        val originalOffer = f.offer
        val original = assertIs<ProvincialAppointmentExecutor.Result.Appointed>(f.execute())
        f.ruler = false
        f.owned = emptySet()
        f.installOffer(f.offer.copy(status = OfficeOfferStatus.PENDING))
        val candidate = f.world.getGeneralById(2)!!
        f.world.applyGeneralDirtyFree(candidate.copy(nationId = 8, meta = candidate.meta +
            (CaptiveState.META_KEY to "malformed-later-custody")))
        f.world.consumeDirtyState()
        f.assertNoEffects {
            // A committed request remains a no-op after eligibility and offer changes.
            assertEquals(original.copy(replay = true), f.executor().execute(
                f.worldId, "request-1", originalOffer, 10, 11) { _, _ -> error("replay must not read fresh sources") })
            assertEquals(ProvincialAppointmentExecutor.Result.RequestConflict, f.execute())
            assertEquals(ProvincialAppointmentExecutor.Result.SourceUnavailable,
                f.execute(expectedWorld = WorldId(2)))
        }
    }

    @Test fun `receipt prevents recreation after tenure is removed`() {
        val f = Fixture()
        f.execute()
        CourtStateStore(f.world, ChangeRecorder()).saveTenures(emptyList())
        f.world.consumeDirtyState()
        f.assertNoEffects {
            assertEquals(ProvincialAppointmentExecutor.Result.RequestConflict,
                f.executor().execute(f.worldId, "request-1", f.offer, 10, 11) { _, _ ->
                    error("consumed request must not execute again") })
        }
        assertTrue(CourtStateStore(f.world, f.recorder).tenures().isEmpty())
    }

    @Test fun `malformed receipt store fails closed without source reads or writes`() {
        val f = Fixture()
        f.world.setGameEnvValue("provincialAppointmentReceipts", "invalid")
        f.world.consumeDirtyState()
        f.assertNoEffects {
            assertEquals(ProvincialAppointmentExecutor.Result.SourceUnavailable,
                f.executor().execute(f.worldId, "request-1", f.offer, 10, 11) { _, _ ->
                    error("invalid receipt store must fail closed") })
        }
    }

    @Test fun `world mismatch and same numeric request in another world stay isolated`() {
        val f = Fixture()
        f.assertNoEffects { assertEquals(ProvincialAppointmentExecutor.Result.SourceUnavailable,
            f.execute(expectedWorld = WorldId(2))) }
        f.execute()
        val other = Fixture(WorldId(2))
        assertFalse(assertIs<ProvincialAppointmentExecutor.Result.Appointed>(other.execute()).replay)
        assertEquals(1, CourtStateStore(other.world, other.recorder).tenures().size)
    }

    @Test fun `execution reads changed ruler captivity and control rather than intake facts`() {
        for (change in 1..3) {
            val f = Fixture()
            f.sources(f.world, f.offer) // Observation at intake, never passed as execution authority.
            when (change) {
                1 -> f.ruler = false
                2 -> {
                    val actor = f.world.getGeneralById(2)!!
                    f.world.applyGeneralDirtyFree(actor.copy(meta = actor.meta + (CaptiveState.META_KEY to
                        CaptiveState(3, "land:test", Phase(196, 1, 1), "capture-1").toMetaValue())))
                }
                3 -> f.owned = emptySet()
            }
            val reason = when (change) {
                1 -> OfficeAppointmentFailure.NOT_RULER
                2 -> OfficeAppointmentFailure.CANDIDATE_UNAVAILABLE
                else -> OfficeAppointmentFailure.SEAT_NOT_OWNED
            }
            f.assertNoEffects { assertEquals(ProvincialAppointmentExecutor.Result.Denied(reason), f.execute()) }
        }
    }

    @Test fun `unaccepted or changed offer is not converted into acceptance`() {
        val f = Fixture()
        val accepted = f.offer
        f.installOffer(accepted.copy(status = OfficeOfferStatus.PENDING))
        f.assertNoEffects { assertEquals(ProvincialAppointmentExecutor.Result.SourceUnavailable,
            f.execute(expected = accepted)) }
        f.assertNoEffects { assertEquals(ProvincialAppointmentExecutor.Result.Denied(OfficeAppointmentFailure.OFFER_NOT_ACCEPTED),
            f.execute()) }
    }

    @Test fun `prepared generation rolls back court store live write on recording failure`() {
        val f = Fixture()
        val session = DeltaGenerationSession()
        val changes = ChangeRecorder(generationSession = session)
        session.prepare()
        f.assertNoEffects { assertFailsWith<IllegalStateException> { f.execute(changes = changes) } }
        assertTrue(changes.kvDirty().isEmpty())
    }
}
