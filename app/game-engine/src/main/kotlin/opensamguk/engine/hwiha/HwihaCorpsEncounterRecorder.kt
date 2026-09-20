package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Reserves the actual participants of a pending encounter; it does not resolve a battle. */
class HwihaCorpsEncounterRecorder(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
) {
    fun defendersAt(actorId: Int, province: StrategicNodeRef.LandProvince): List<HwihaDeployedCorps>? {
        val projection = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection() ?: return null
        val presence = HwihaMilitaryPresenceProvider(world, topology, metrics).assess(actorId)
            as? MilitaryPresenceAssessment.Ready ?: return null
        val defenders = presence.hostileCorps.filter { corps ->
            projection.people.single { it.id == corps.commanderGeneralId }.node == province
        }
        // Joining an unresolved encounter requires a separate reinforcement contract.
        if (defenders.isEmpty() || defenders.any { corps ->
                projection.people.single { it.id == corps.commanderGeneralId }.inBattle
            }) return null
        return defenders
    }

    fun record(attacker: HwihaDeployedCorps, defenders: List<HwihaDeployedCorps>, checkpoint: HwihaMarchCheckpoint) {
        require(checkpoint.stop == LandMarchStop.ENCOUNTER)
        val index = checkpoint.cursor.edgeIndex
        fun node(key: String): StrategicNodeRef.LandProvince {
            require(key.startsWith("land:"))
            return StrategicNodeRef.LandProvince(key.removePrefix("land:"))
        }
        val encounter = HwihaCorpsEncounter(
            HwihaEncounterParticipant.from(attacker), defenders.map(HwihaEncounterParticipant::from),
            node(checkpoint.path.nodeKeys[index]), node(checkpoint.path.nodeKeys[index - 1]),
            checkpoint.lastAdvancedAt, topology.topologyRevision, topology.contentHash,
        )
        val participants = (listOf(attacker) + defenders).sortedBy { it.commanderGeneralId }
        // Validate all participants before recording any metadata. The enclosing turn flush is atomic.
        for (corps in participants) {
            val general = requireNotNull(world.getGeneralById(corps.commanderGeneralId))
            require(world.positionOf(general.id) == encounter.province)
            require(HwihaCorpsEncounter.META_KEY !in general.meta)
            require(HwihaEncounterDeployment.META_KEY !in general.meta)
            require(HwihaEncounterRelations.META_KEY !in general.meta)
            require(HwihaEncounterForces.META_KEY !in general.meta)
        }
        val value = encounter.toMetaValue()
        val deployment = HwihaEncounterDeployment.defaultMetaValue(encounter, cells)
        val projection = requireNotNull(HwihaDeploymentExecutor(world, recorder, topology, metrics).projection())
        val relations = HwihaEncounterRelations.capture(encounter, projection,
            world.listDiplomacy().filter { it.state == 0 }.mapTo(linkedSetOf()) { it.fromNationId to it.toNationId })
        val liveUnits = world.listBugoks().associateBy { it.id }
        val forces = HwihaEncounterForces(encounter.encounterId,
            participants.flatMap { corps -> corps.bugokIds.map { id ->
                val unit = requireNotNull(liveUnits[id])
                EncounterUnitForce(unit.id, unit.masterGeneralId, corps.commanderGeneralId, unit.crewTypeId,
                    unit.troops, unit.training, unit.morale, unit.fatigue, unit.provisions, unit.commanderRetainerId)
            } }, participants.map { corps ->
                val stats = requireNotNull(world.getGeneralById(corps.commanderGeneralId)).stats
                EncounterCommanderForce(corps.commanderGeneralId, stats.leadership, stats.strength,
                    stats.intelligence, stats.politics, stats.charm)
            }).also { it.requireBinding(encounter) }
        val sealedRelations = relations.toMetaValue()
        val sealedForces = forces.toMetaValue()
        for (corps in participants) {
            val before = requireNotNull(world.getGeneralById(corps.commanderGeneralId))
            val after = before.copy(meta = before.meta + (HwihaCorpsEncounter.META_KEY to value) +
                (HwihaEncounterDeployment.META_KEY to deployment) +
                (HwihaEncounterRelations.META_KEY to sealedRelations) + (HwihaEncounterForces.META_KEY to sealedForces))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
            if (corps.commanderGeneralId != attacker.commanderGeneralId) {
                world.pushLog(LogEntryDraft(scope = "general", category = "action",
                    text = "군단이 조우하여 전투 처리를 기다리고 있습니다.", generalId = before.id, nationId = before.nationId))
            }
        }
    }
}
