package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.infra.seed.UnitProfilesJson
import opensamguk.logic.input.*
import opensamguk.logic.war.*
import opensamguk.logic.world.*

/**
 * Resolves a sealed pending encounter on the attacker commander's personal turn (§5.1 step 5) and
 * settles it through the recorder: unit losses, retreat, ended deployments, renown events and captives.
 *
 * The encounter was sealed on the turn the attacker entered the province; its plans, forces, relations,
 * combat profiles, layout and empty journal were frozen then. Resolution replays only those sealed
 * values ([EncounterResolution]) — live changes since sealing never alter the battle. An encounter
 * whose combat could not be prepared (unsupported unit, no battlefield) ends without battle; a transient
 * sealed-state mismatch has a bounded retry window. Neither case traps the march forever.
 */
class EncounterResolver(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
    private val outcomes: WarOutcomeListener = WarOutcomeListener.NONE,
) {
    sealed interface Resolution {
        data object NotPending : Resolution
        data object NotAttacker : Resolution
        data class Unavailable(val reason: String) : Resolution
        data class Disbanded(val reason: String, val encounter: CorpsEncounter) : Resolution
        data class Resolved(val result: EncounterResolution.Result, val encounter: CorpsEncounter) : Resolution
    }

    fun resolvePending(generalId: Int): Resolution {
        if (world.ruleProfile != RuleProfile.HWIHA) return Resolution.NotPending
        val actor = world.getGeneralById(generalId) ?: return Resolution.NotPending
        if (CorpsEncounter.META_KEY !in actor.meta) return Resolution.NotPending
        val encounter = try { CorpsEncounter.read(actor.meta, topology) }
            catch (_: IllegalArgumentException) { return Resolution.Unavailable("INVALID_ENCOUNTER") }
            ?: return Resolution.NotPending
        if (encounter.attacker.commanderGeneralId != generalId) return Resolution.NotAttacker
        val permanent = permanentUnavailableReason(actor.meta, encounter)
        if (permanent != null) return unavailable(encounter, permanent, permanent = true)
        val sealed = try { sealedOf(actor.meta, encounter) } catch (_: IllegalArgumentException) { null }
            ?: return unavailable(encounter, "INVALID_SEALED_STATE")
        val (forces, relations, combat, plans, deployment) = sealed
        val journal = try { BattleJournal.read(actor.meta) } catch (_: IllegalArgumentException) {
            return unavailable(encounter, "INVALID_JOURNAL")
        } ?: return unavailable(encounter, "BATTLE_NOT_READY")
        // Every participant must still carry byte-identical sealed records; disagreement is corruption.
        val participants = (listOf(encounter.attacker) + encounter.defenders).map { it.commanderGeneralId }.sorted()
        for (id in participants) {
            val meta = world.getGeneralById(id)?.meta ?: return unavailable(encounter, "PARTICIPANT_MISSING")
            if (SEALED_KEYS.any { meta[it] != actor.meta[it] }) return unavailable(encounter, "PARTICIPANT_MISMATCH")
        }
        val result = EncounterResolution.resolve(encounter, forces, relations, combat, plans, deployment, journal)
        settle(encounter, forces, result)
        return Resolution.Resolved(result, encounter)
    }

    /** These reasons are fixed by the sealed inputs, so another phase cannot make the battle ready. */
    private fun permanentUnavailableReason(meta: Map<String, Any?>, encounter: CorpsEncounter): String? {
        return try {
            val forces = EncounterForces.read(meta, encounter) ?: return null
            val combat = EncounterCombatProfiles.read(meta, forces, UnitProfilesJson.loadDefault())
            if (combat != null && !combat.ready) return "UNIT_PROFILE_UNAVAILABLE"
            when (EncounterDeployment.read(meta, encounter, cells)) {
                is EncounterDeployment.Result.TerrainUnavailable,
                EncounterDeployment.Result.InsufficientDefenderCapacity -> "BATTLEFIELD_UNAVAILABLE"
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    private fun unavailable(encounter: CorpsEncounter, reason: String, permanent: Boolean = false): Resolution {
        val state = world.getState()
        val now = Phase(state.currentYear, state.currentMonth, state.currentPhase)
        if (!permanent && now < encounter.phase.plus(CampaignBalance.ENCOUNTER_UNAVAILABLE_RETRY_PHASES))
            return Resolution.Unavailable(reason)
        val participants = (listOf(encounter.attacker) + encounter.defenders).sortedBy { it.commanderGeneralId }
        val record = linkedMapOf<String, Any?>("version" to 1, "encounterId" to encounter.encounterId,
            "reason" to reason, "resolvedAt" to now.toMetaValue(), "province" to encounter.province.id)
        for (participant in participants) {
            endDeployment(participant)
            updateMeta(participant.commanderGeneralId) { it - SEALED_KEYS + (DISBAND_RECORD_KEY to record) }
            if (world.getGeneralById(participant.commanderGeneralId) != null) {
                Records.general(world, participant.commanderGeneralId, RecordKind.ENCOUNTER_DISBANDED,
                    "조우 전투를 준비할 수 없어 군단이 이 지역에서 행군을 멈췄습니다($reason).",
                    mapOf("encounterId" to encounter.encounterId, "province" to encounter.province.id, "reason" to reason))
            }
        }
        return Resolution.Disbanded(reason, encounter)
    }

    private data class Sealed(val forces: EncounterForces, val relations: EncounterRelations,
        val combat: EncounterCombatProfiles, val plans: BattlePlans, val deployment: EncounterDeployment)

    private fun sealedOf(meta: Map<String, Any?>, encounter: CorpsEncounter): Sealed? {
        val forces = EncounterForces.read(meta, encounter) ?: return null
        val relations = EncounterRelations.read(meta, encounter) ?: return null
        val combat = EncounterCombatProfiles.read(meta, forces, UnitProfilesJson.loadDefault()) ?: return null
        if (!combat.ready) return null
        val plans = BattlePlans.read(meta, encounter) ?: return null
        val deployment = (EncounterDeployment.read(meta, encounter, cells) as? EncounterDeployment.Result.Ready)
            ?.deployment ?: return null
        return Sealed(forces, relations, combat, plans, deployment)
    }

    private fun settle(encounter: CorpsEncounter, forces: EncounterForces,
        result: EncounterResolution.Result) {
        val state = world.getState()
        val now = Phase(state.currentYear, state.currentMonth, state.currentPhase)
        // 1. Losses: subtract the battle's casualties from the live units (never overwrite later changes).
        val finals = result.units.associateBy { it.bugokId }
        // A unit row cannot hold zero troops (general_bugok_troops_ck): an annihilated unit is removed.
        val destroyed = sortedSetOf<Int>()
        for (sealedUnit in forces.units.sortedBy { it.bugokId }) {
            val live = world.getBugokById(sealedUnit.bugokId) ?: continue
            val final = finals.getValue(sealedUnit.bugokId)
            val lost = sealedUnit.troops - final.troops
            val troops = (live.troops - lost).coerceAtLeast(0)
            if (troops == 0) { world.removeBugok(live.id); destroyed += live.id; continue }
            val next = live.copy(troops = troops, morale = final.morale, fatigue = final.fatigue)
            if (next != live) world.updateBugok(next)
        }
        val participants = listOf(encounter.attacker) + encounter.defenders
        val record = linkedMapOf<String, Any?>("version" to 1, "encounterId" to encounter.encounterId,
            "ruleVersion" to EncounterResolution.RULE_VERSION,
            "resolvedAt" to now.toMetaValue(), "province" to encounter.province.id,
            "approachFrom" to encounter.approachFrom.id, "outcome" to result.outcome.name,
            "barrier" to result.barrier.name, "rounds" to result.rounds,
            "statuses" to result.statuses.entries.map { linkedMapOf("generalId" to it.key, "status" to it.value.name) },
            "captives" to result.captives.entries.map { linkedMapOf("captiveGeneralId" to it.key, "captorGeneralId" to it.value) },
            "journal" to result.journal.toMetaValue(), "replayHash" to result.replayHash)
        // 2. Sealed records leave every participant together; a compact replay record stays.
        for (participant in participants.sortedBy { it.commanderGeneralId }) {
            updateMeta(participant.commanderGeneralId) { meta -> meta - SEALED_KEYS + (BATTLE_RECORD_KEY to record) }
        }
        // 3. Surviving corps drop annihilated units; a corps with none left ends like a loser.
        val ended = result.losers.toMutableSet()
        for (participant in participants.filter { it.commanderGeneralId !in ended }) {
            if (participant.bugokIds.none { it in destroyed }) continue
            val remaining = participant.bugokIds.filter { it !in destroyed }
            if (remaining.isEmpty()) { endDeployment(participant); ended += participant.commanderGeneralId; continue }
            updateMeta(participant.ownerGeneralId) { meta ->
                val deployment = DeploymentState.read(meta) ?: return@updateMeta meta
                meta + (DeploymentState.META_KEY to DeploymentState(deployment.corps.map {
                    if (it.orderId == participant.orderId) it.copy(bugokIds = remaining.sorted()) else it
                }).toMetaValue())
            }
        }
        // 4. Losers end their deployment. The attacker withdraws to the province it came from.
        for (loser in result.losers) {
            val participant = participants.single { it.commanderGeneralId == loser }
            endDeployment(participant)
            if (loser == encounter.attacker.commanderGeneralId) {
                check(recorder.moveGeneral(world, loser, encounter.approachFrom) is GeneralPositionChangeResult.Changed) {
                    "Validated retreat position transition was rejected"
                }
            }
        }
        // 5. A victorious attacker keeps its order; its march resumes next turn from the won province.
        if (encounter.attacker.commanderGeneralId in result.winners && encounter.attacker.commanderGeneralId !in ended) {
            updateMeta(encounter.attacker.commanderGeneralId) { meta ->
                val march = try { CorpsMarchState.read(meta, topology, metrics) }
                    catch (_: IllegalArgumentException) { null } ?: return@updateMeta meta
                val checkpoint = march.checkpoint
                val stop = if (checkpoint.cursor.edgeIndex == checkpoint.path.edgeIds.size) LandMarchStop.ARRIVED
                    else LandMarchStop.BUDGET_EXHAUSTED
                meta + (CorpsMarchState.META_KEY to march.copy(checkpoint = checkpoint.copy(stop = stop)).toMetaValue())
            }
        }
        // 6. Renown events go through the war-outcome boundary exactly once; a battle with no winner is not reported.
        if (result.winners.isNotEmpty()) outcomes.onEncounterResolved(result.winners, result.losers)
        // The provisional captive marker.
        for ((captive, captor) in result.captives) {
            updateMeta(captive) { meta -> meta + (CAPTIVE_KEY to linkedMapOf("version" to 1, "captorGeneralId" to captor,
                "encounterId" to encounter.encounterId, "capturedAt" to now.toMetaValue())) }
        }
        // 7. Private logs, in commander id order.
        val attackerId = encounter.attacker.commanderGeneralId
        for (id in participants.map { it.commanderGeneralId }.sorted()) {
            val won = id in result.winners
            val text = when {
                id == attackerId && won -> "조우 전투에서 승리했습니다(${result.rounds}회차)."
                id == attackerId -> "조우 전투에서 물러나 출병을 멈췄습니다(${result.rounds}회차)."
                won -> "조우 전투에서 진지를 지켜 냈습니다(${result.rounds}회차)."
                id in result.losers -> "조우 전투에서 패해 군단이 흩어졌습니다(${result.rounds}회차)."
                else -> "조우 전투가 끝났습니다(${result.rounds}회차)."
            } + if (id in result.captives) " 지휘관이 사로잡혔습니다." else ""
            log(id, text)
        }
    }

    private fun endDeployment(participant: EncounterParticipant) {
        val ownerId = participant.ownerGeneralId
        updateMeta(ownerId) { meta ->
            val deployment = try { DeploymentState.read(meta) } catch (_: IllegalArgumentException) { null }
                ?: return@updateMeta meta - DeploymentState.META_KEY
            val rest = deployment.corps.filterNot { it.orderId == participant.orderId }
            if (rest.isEmpty()) meta - DeploymentState.META_KEY
            else meta + (DeploymentState.META_KEY to DeploymentState(rest).toMetaValue())
        }
        updateMeta(participant.commanderGeneralId) { meta ->
            meta - CorpsOrder.META_KEY - CorpsMarchState.META_KEY
        }
    }

    private fun updateMeta(generalId: Int, change: (Map<String, Any?>) -> Map<String, Any?>) {
        val before = world.getGeneralById(generalId) ?: return
        val meta = change(before.meta)
        if (meta == before.meta) return
        val after = before.copy(meta = meta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    private fun log(generalId: Int, text: String) = world.pushLog(LogEntryDraft(scope = "general", category = "action",
        text = text, generalId = generalId, nationId = world.getGeneralById(generalId)?.nationId))

    companion object {
        const val BATTLE_RECORD_KEY = "hwihaLastBattle"
        const val DISBAND_RECORD_KEY = "hwihaLastEncounterDisbanded"
        const val CAPTIVE_KEY = "hwihaCaptive"
        val SEALED_KEYS = listOf(CorpsEncounter.META_KEY, EncounterDeployment.META_KEY,
            EncounterRelations.META_KEY, EncounterForces.META_KEY, EncounterCombatProfiles.META_KEY,
            BattlePlans.META_KEY, BattleJournal.META_KEY)
    }
}
