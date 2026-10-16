package opensamguk.engine.campaign

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef.LandProvince
import java.time.Instant
import kotlin.test.*

class BattleOriginProducerTest {
    private val at = Instant.parse("0200-01-01T00:00:00Z")
    private fun general(id: Int, user: String?, npc: Int) = TurnGeneral(id = id, name = "g$id",
        nationId = id, cityId = 1, troopId = 0, stats = GeneralStats(110, 70, 60), experience = 0,
        dedication = 0, officerLevel = 0, gold = 100, turnTime = at, userId = user, npcState = npc)
    private fun world() = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = at),
        generals = listOf(general(1, "42", 0), general(2, null, 2), general(3, null, 2)),
        nations = emptyList(), cities = emptyList(), worldId = WorldId(1),
        bugoks = listOf(Bugok(11, 1, "a", 500, 1, 20, 30, provisions = 600),
            Bugok(22, 2, "b", 800, 1, 100, 0, commanderRetainerId = 9),
            Bugok(23, 2, "c", 800, 1, 100, 0, commanderRetainerId = 9))))
    private fun encounter() = CorpsEncounter(EncounterParticipant("a", 1, 1, 1, listOf(11)),
        listOf(EncounterParticipant("b", 2, 3, 2, listOf(22, 23))), LandProvince("B"), LandProvince("A"),
        Phase(200, 1, 1), "qa", "a".repeat(64))
    private fun forces(e: CorpsEncounter) = EncounterForces(e.encounterId,
        listOf(EncounterUnitForce(11, 1, 1, 1, 500, 20, 30, 0, 600, null),
            EncounterUnitForce(22, 2, 3, 1, 800, 100, 0, 0, 0, 9),
            EncounterUnitForce(23, 2, 3, 1, 800, 100, 0, 0, 0, 9)),
        listOf(EncounterCommanderForce(1, 110, 70, 60, 0, 0), EncounterCommanderForce(3, 90, 70, 60, 0, 0)))
    private fun relations(e: CorpsEncounter) = EncounterRelations.read(mapOf(EncounterRelations.META_KEY to mapOf(
        "version" to 1, "encounterId" to e.encounterId, "snapshotId" to relationHash(e), "pairs" to listOf(
            mapOf("firstCommanderId" to 1, "secondCommanderId" to 3, "hostile" to true)))), e)!!
    private fun relationHash(e: CorpsEncounter): String {
        val buffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { out -> out.writeUTF("encounterRelations:v1"); out.writeUTF(e.encounterId)
            out.writeInt(1); out.writeInt(1); out.writeInt(3); out.writeBoolean(true) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    @Test fun `producer preserves shared commander raw forces and transports draft without a fence`() {
        val world = world(); val e = encounter(); val f = forces(e)
        val result = BattleOriginProducer.draft(e, f, relations(e), world, "b".repeat(64))
        val draft = assertNotNull(result.draft)
        assertEquals(f.toMetaValue(), draft.forces.toMetaValue())
        assertEquals(110, draft.forces.commanders.first().leadership)
        val recorder = ChangeRecorder(); recorder.recordCampaignBattleOrigin(draft)
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(listOf(draft), payload.campaignBattleOriginDrafts)
        assertFalse(payload.worldStateUpdate.containsKey("expected_world_version"))
        assertEquals(listOf(draft), payload.copy(worldStateUpdate = payload.worldStateUpdate +
            mapOf("expected_world_version" to 0L, "writer_epoch" to 1L)).campaignBattleOriginDrafts)
    }
    @Test fun `missing relations defers without changing automatic settlement metadata`() {
        val world = world(); val e = encounter(); val before = world.listGenerals().map { it.meta }
        assertEquals("RELATIONS_MISSING", BattleOriginProducer.draft(e, forces(e), null, world, "b".repeat(64)).reason)
        assertEquals(before, world.listGenerals().map { it.meta })
    }
    @Test fun `invalid owner and duplicate nonowner account omit whole encounter`() {
        val world = world(); val e = encounter()
        world.applyGeneralDirtyFree(world.getGeneralById(1)!!.copy(userId = "042"))
        val invalid = BattleOriginProducer.draft(e, forces(e), relations(e), world, "b".repeat(64))
        assertNull(invalid.draft); assertEquals("OWNER_AUTHORITY_INVALID", invalid.reason)
        assertEquals("NONCANONICAL_ACCOUNT", invalid.detail)
        world.applyGeneralDirtyFree(world.getGeneralById(1)!!.copy(userId = "42"))
        world.applyGeneralDirtyFree(world.getGeneralById(3)!!.copy(userId = "42", npcState = 0))
        assertEquals("DUPLICATE_ACCOUNT", BattleOriginProducer.draft(e, forces(e), relations(e), world, "b".repeat(64)).detail)
    }
    @Test fun `bugok revision changes only with content and ignores caller revision`() {
        val world = world(); val before = world.getBugokById(11)!!
        assertEquals(1L, before.revision)
        assertEquals(1L, world.updateBugok(before.copy(revision = 99))!!.revision)
        assertEquals(2L, world.updateBugok(before.copy(provisions = 601, revision = 99))!!.revision)
        assertEquals(2L, world.updateBugok(world.getBugokById(11)!!.copy(revision = 1))!!.revision)
        val payload = DatabaseHooks.toFlushPayload(world, ChangeRecorder(), world.consumeDirtyState())
        assertEquals(2L, payload.updatedBugoks.single().revision)
    }
    @Test fun `draft channel survives checkpoint rollback and clears after a successful flush`() {
        val world = world(); val e = encounter(); val draft = BattleOriginProducer.draft(e, forces(e), relations(e), world, "b".repeat(64)).draft!!
        val recorder = ChangeRecorder(); val checkpoint = recorder.checkpoint()
        recorder.recordCampaignBattleOrigin(draft); assertTrue(recorder.isDirty)
        recorder.restore(checkpoint); assertTrue(recorder.campaignBattleOriginDrafts().isEmpty())
        recorder.recordCampaignBattleOrigin(draft); recorder.clear()
        assertFalse(recorder.isDirty); assertTrue(recorder.campaignBattleOriginDrafts().isEmpty())
    }
}
