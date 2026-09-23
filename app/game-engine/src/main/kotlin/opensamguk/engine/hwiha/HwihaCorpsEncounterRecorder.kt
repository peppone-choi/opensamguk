package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.*
import opensamguk.logic.war.hwiha.HwihaEncounterCombatProfiles
import opensamguk.logic.war.hwiha.HwihaBattlePlans
import opensamguk.logic.war.hwiha.HwihaBattleJournal
import opensamguk.logic.war.hwiha.HwihaBattlePlayback
import opensamguk.infra.seed.HwihaUnitProfilesJson
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

    /** @return the recorded encounter id — the attacker's own march record carries it. */
    fun record(attacker: HwihaDeployedCorps, defenders: List<HwihaDeployedCorps>, checkpoint: HwihaMarchCheckpoint): String {
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
            require(HwihaEncounterCombatProfiles.META_KEY !in general.meta)
            require(HwihaBattlePlans.META_KEY !in general.meta)
            require(HwihaBattleJournal.META_KEY !in general.meta)
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
        val plans = HwihaBattlePlans.defaultFor(encounter)
        val battlePlans = plans.toMetaValue()
        val sealedRelations = relations.toMetaValue()
        val sealedForces = forces.toMetaValue()
        val combat = HwihaEncounterCombatProfiles.capture(forces, HwihaUnitProfilesJson.loadDefault())
        val combatProfiles = combat.toMetaValue()
        val ready = HwihaEncounterDeployment.read(mapOf(HwihaEncounterDeployment.META_KEY to deployment),encounter,cells)
            as? HwihaEncounterDeployment.Result.Ready
        val journal = if (ready != null && combat.ready)
            HwihaBattlePlayback(encounter,forces,relations,combat,plans,ready.deployment).initialJournal().toMetaValue()
            else null
        for (corps in participants) {
            val before = requireNotNull(world.getGeneralById(corps.commanderGeneralId))
            val after = before.copy(meta = before.meta + (HwihaCorpsEncounter.META_KEY to value) +
                (HwihaEncounterDeployment.META_KEY to deployment) +
                (HwihaEncounterRelations.META_KEY to sealedRelations) + (HwihaEncounterForces.META_KEY to sealedForces) +
                (HwihaEncounterCombatProfiles.META_KEY to combatProfiles) + (HwihaBattlePlans.META_KEY to battlePlans) +
                (journal?.let { mapOf(HwihaBattleJournal.META_KEY to it) } ?: emptyMap()))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
            if (corps.commanderGeneralId != attacker.commanderGeneralId) {
                // A participant may know that it was engaged and where; forces and plans stay sealed (#343).
                HwihaRecords.general(world, before.id, HwihaRecordKind.ENCOUNTER_PENDING,
                    "군단이 조우하여 전투 처리를 기다리고 있습니다.",
                    linkedMapOf("encounterId" to encounter.encounterId, "province" to encounter.province.canonicalKey),
                    nationId = before.nationId)
            }
        }
        return encounter.encounterId
    }
}
