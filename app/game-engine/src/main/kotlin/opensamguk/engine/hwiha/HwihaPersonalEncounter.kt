package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Settles a lone traveler's contact on entry. Only people participate; no bugok is created or changed. */
class HwihaPersonalEncounter(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val reactions: HwihaMarchReactionPolicy,
    private val outcomes: HwihaWarOutcomeListener = HwihaWarOutcomeListener.NONE,
) {
    fun settle(actorId: Int, applied: HwihaTravelExecution.Applied) {
        val checkpoint = applied.state.checkpoint
        if (checkpoint.stop != LandMarchStop.ENCOUNTER) return
        val province = applied.movement.reachedNodes.last()
        val defenders = hostileDefenders(actorId, province)
        val approach = StrategicNodeRef.LandProvince(checkpoint.path.nodeKeys[checkpoint.cursor.edgeIndex - 1]
            .removePrefix("land:"))
        if (defenders.isEmpty()) {
            check(reactions.schemeContact(world, actorId, province)) { "Encounter lost its defender" }
            val stop = if (checkpoint.cursor.edgeIndex == checkpoint.path.edgeIds.size) LandMarchStop.ARRIVED
                else LandMarchStop.BUDGET_EXHAUSTED
            update(actorId) { it.copy(meta = it.meta + (HwihaTravelState.META_KEY to
                applied.state.copy(checkpoint = checkpoint.copy(stop = stop)).toMetaValue())) }
            HwihaRecords.general(world, actorId, RecordKind.MARCH_DIRECT,
                "설치 계책을 만나 행군을 멈췄습니다.", mapOf("orderId" to applied.state.orderId,
                    "province" to province.canonicalKey, "stop" to "SCHEME_CONTACT"))
            return
        }
        val attacker = checkNotNull(world.getGeneralById(actorId))
        val defenderPeople = defenders.map { checkNotNull(world.getGeneralById(it.commanderGeneralId)) }
        val conditions = try {
            (listOf(attacker) + defenderPeople).associate { person ->
                person.id to (HwihaPersonalTravelCondition.read(person.meta) ?: HwihaPersonalTravelCondition.INITIAL)
            }
        } catch (_: IllegalArgumentException) {
            check(recorder.moveGeneral(world, actorId, approach) is GeneralPositionChangeResult.Changed) {
                "Invalid personal encounter state could not retreat"
            }
            update(actorId) { it.copy(meta = it.meta - HwihaTravelState.META_KEY) }
            HwihaRecords.general(world, actorId, RecordKind.INPUT_REJECTED,
                "개인 조우 상태를 읽을 수 없어 이전 省으로 물러났습니다.",
                mapOf("inputId" to applied.state.inputId, "code" to "STATE_UNAVAILABLE"))
            return
        }
        val attackerCondition = conditions.getValue(actorId)
        fun fighter(person: TurnGeneral, condition: HwihaPersonalTravelCondition) = HwihaPersonalEncounterBattle.Fighter(
            person.id, person.stats.leadership.coerceIn(0, 100), person.stats.strength.coerceIn(0, 100),
            person.injury.coerceIn(0, 100), condition.fatigue, condition.morale)
        val battle = HwihaPersonalEncounterBattle.resolve(fighter(attacker, attackerCondition),
            defenderPeople.map { person -> fighter(person, conditions.getValue(person.id)) })
        val encounterId = "personal:${applied.state.orderId}:${checkpoint.cursor.edgeIndex}"
        val replay = linkedMapOf<String, Any?>("version" to 1, "ruleVersion" to HwihaPersonalEncounterBattle.RULE_VERSION,
            "orderId" to applied.state.orderId,
            "encounterId" to encounterId, "province" to province.id, "approachFrom" to approach.id,
            "attackerGeneralId" to actorId, "defenderGeneralId" to battle.defenderGeneralId,
            "outcome" to battle.outcome.name, "rounds" to battle.rounds,
            "attackerRemaining" to battle.attackerRemaining, "defenderRemaining" to battle.defenderRemaining)
        val updatedCondition = attackerCondition.copy(fatigue = battle.attackerFatigue, morale = battle.attackerMorale)
        update(actorId) { before ->
            val travel = if (battle.outcome == HwihaPersonalEncounterBattle.Outcome.WON) {
                val stop = if (checkpoint.cursor.edgeIndex == checkpoint.path.edgeIds.size) LandMarchStop.ARRIVED
                    else LandMarchStop.BUDGET_EXHAUSTED
                mapOf(HwihaTravelState.META_KEY to applied.state.copy(checkpoint = checkpoint.copy(stop = stop)).toMetaValue())
            } else emptyMap()
            before.copy(injury = battle.attackerInjury, meta =
                (before.meta - HwihaTravelState.META_KEY) + travel +
                    (HwihaPersonalTravelCondition.META_KEY to updatedCondition.toMetaValue()) +
                    (REPLAY_KEY to replay) +
                    (if (battle.outcome == HwihaPersonalEncounterBattle.Outcome.CAPTURED)
                        mapOf(HwihaEncounterResolver.CAPTIVE_KEY to linkedMapOf("version" to 1,
                            "captorGeneralId" to battle.defenderGeneralId, "encounterId" to encounterId,
                            "capturedAt" to HwihaPhase(world.getState().currentYear, world.getState().currentMonth,
                                world.getState().currentPhase).toMetaValue())) else emptyMap()))
        }
        update(battle.defenderGeneralId) { it.copy(injury = battle.defenderInjury,
            meta = it.meta + (REPLAY_KEY to replay)) }
        // A captured traveler remains with the captor, so a local persuasion action can target them.
        if (battle.outcome == HwihaPersonalEncounterBattle.Outcome.RETREATED) {
            check(recorder.moveGeneral(world, actorId, approach) is GeneralPositionChangeResult.Changed) {
                "Personal encounter retreat failed"
            }
        }
        val won = battle.outcome == HwihaPersonalEncounterBattle.Outcome.WON
        val winners = if (won) listOf(actorId) else listOf(battle.defenderGeneralId)
        val losers = if (won) listOf(battle.defenderGeneralId) else listOf(actorId)
        outcomes.onEncounterResolved(winners, losers)
        HwihaRecords.general(world, actorId, RecordKind.PERSONAL_ENCOUNTER,
            when (battle.outcome) {
                HwihaPersonalEncounterBattle.Outcome.WON -> "개인 조우 전투에서 승리했습니다."
                HwihaPersonalEncounterBattle.Outcome.RETREATED -> "개인 조우 전투에서 패해 이전 省으로 물러났습니다."
                HwihaPersonalEncounterBattle.Outcome.CAPTURED -> "개인 조우 전투에서 패해 사로잡혔습니다."
            }, mapOf("encounterId" to encounterId, "outcome" to battle.outcome.name,
                "province" to province.canonicalKey, "rounds" to battle.rounds))
        HwihaRecords.general(world, battle.defenderGeneralId, RecordKind.PERSONAL_ENCOUNTER,
            if (won) "진입한 적 장수와의 개인 조우 전투에서 패했습니다."
                else "진입한 적 장수와의 개인 조우 전투에서 승리했습니다.",
            mapOf("encounterId" to encounterId, "outcome" to if (won) "LOST" else "WON",
                "province" to province.canonicalKey))
    }

    private fun hostileDefenders(actorId: Int, province: StrategicNodeRef.LandProvince): List<HwihaDeployedCorps> {
        val projection = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection() ?: return emptyList()
        val presence = HwihaMilitaryPresenceProvider(world, topology, metrics).assess(actorId)
            as? MilitaryPresenceAssessment.Ready ?: return emptyList()
        return presence.hostileCorps.filter { corps ->
            projection.people.single { it.id == corps.commanderGeneralId }.node == province &&
                !projection.people.single { it.id == corps.commanderGeneralId }.inBattle
        }.sortedBy { it.commanderGeneralId }
    }

    private fun update(id: Int, transform: (TurnGeneral) -> TurnGeneral) {
        val before = checkNotNull(world.getGeneralById(id))
        val after = transform(before)
        if (after == before) return
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    companion object {
        const val REPLAY_KEY = "hwihaLastPersonalEncounter"

        /** A busy defender cannot join another encounter; malformed reaction authority never grants entry. */
        fun entryAt(world: InMemoryTurnWorld, military: HwihaMilitaryPresenceProvider,
            reactions: HwihaMarchReactionPolicy, actorId: Int,
            node: StrategicNodeRef.LandProvince): LandMarchEntry {
            val entry = military.directEntryAt(actorId, node, reactions)
            if (entry != LandMarchEntry.ENCOUNTER) return entry
            val ready = military.assess(actorId) as? MilitaryPresenceAssessment.Ready
            val hostile = ready?.hostileCorps?.filter { world.positionOf(it.commanderGeneralId) == node }.orEmpty()
            if (hostile.any { world.generalPositionSnapshot()?.stateFor(it.commanderGeneralId)?.battlefield != null } ||
                hostile.isEmpty() && !reactions.directInterceptsAt(world, actorId, node) &&
                !reactions.schemeContact(world, actorId, node)) return LandMarchEntry.UNAVAILABLE
            return entry
        }
    }
}
