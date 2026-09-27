package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource

/** Resolves one field-phase personal action at the general's current land position. */
class PersonalHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: DomesticContext,
    private val design: PersonalDesign = PersonalDesign.CANON,
) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): TurnOutcome {
        fun reject(reason: PersonalFailure) = TurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(PersonalFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(PersonalFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && NpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return TurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = PersonalInput.parse(actorId, inputId, rawJson)
            ?: return reject(PersonalFailure.INVALID_INPUT)
        if (design.status != PersonalDesign.CONFIRMED)
            return TurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val prior = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (prior?.get("turn") == turnToken) {
            if (prior["inputId"] == inputId && prior["requestId"] == requestId)
                return TurnOutcome.Applied(inputId, (prior["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(PersonalFailure.ALREADY_PROCESSED)
        }
        val check = PersonalRules.assess(request, context.projection(world))
        if (check is PersonalAssessment.Rejected) return reject(check.reason)
        val condition = (check as PersonalAssessment.Eligible).condition
        val exploring = inputId == PersonalInput.TRAVEL
        val experienceGain = if (exploring) design.travelExperience else 0
        val dedicationGain = if (exploring) design.travelDedication else 0
        val nextExperience: Int
        val nextDedication: Int
        try {
            nextExperience = Math.addExact(actor.experience, experienceGain)
            nextDedication = Math.addExact(actor.dedication, dedicationGain)
        } catch (_: ArithmeticException) { return reject(PersonalFailure.STATE_UNAVAILABLE) }
        val effects = mutableListOf<String>()
        val next = when (inputId) {
            PersonalInput.TRAVEL -> actor
            PersonalInput.SELF_TRAIN -> {
                val stat = checkNotNull(request.trainingStat)
                val grown = when (stat) {
                    TrainingStat.LEADERSHIP -> actor.stats.copy(leadership = (actor.stats.leadership + design.trainingStatGain).coerceAtMost(design.trainingStatCap))
                    TrainingStat.STRENGTH -> actor.stats.copy(strength = (actor.stats.strength + design.trainingStatGain).coerceAtMost(design.trainingStatCap))
                    TrainingStat.INTELLIGENCE -> actor.stats.copy(intelligence = (actor.stats.intelligence + design.trainingStatGain).coerceAtMost(design.trainingStatCap))
                    TrainingStat.POLITICS -> actor.stats.copy(politics = (actor.stats.politics + design.trainingStatGain).coerceAtMost(design.trainingStatCap))
                    TrainingStat.CHARM -> actor.stats.copy(charm = (actor.stats.charm + design.trainingStatGain).coerceAtMost(design.trainingStatCap))
                }
                val after = condition.copy(fatigue = (condition.fatigue + design.trainingFatigueGain).coerceAtMost(100))
                effects += "${stat.wireName}:+${design.trainingStatGain}"
                effects += "fatigue:+${after.fatigue - condition.fatigue}"
                actor.copy(stats = grown, meta = actor.meta + (PersonalTravelCondition.META_KEY to after.toMetaValue()))
            }
            PersonalInput.RECUPERATE -> {
                val injury = (actor.injury - design.recuperationInjuryRecovery).coerceAtLeast(0)
                val after = condition.copy(fatigue = (condition.fatigue - design.recuperationFatigueRecovery).coerceAtLeast(0))
                effects += "injury:${injury - actor.injury}"
                effects += "fatigue:${after.fatigue - condition.fatigue}"
                actor.copy(injury = injury, meta = actor.meta + (PersonalTravelCondition.META_KEY to after.toMetaValue()))
            }
            else -> return reject(PersonalFailure.INVALID_INPUT)
        }
        if (exploring) {
            effects += "experience:+$experienceGain"
            effects += "dedication:+$dedicationGain"
        }
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val grown = next.copy(experience = nextExperience, dedication = nextDedication,
            meta = next.meta + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(grown))
        world.applyGeneralDirtyFree(grown)
        if (exploring) RenownEventRecorder(world, recorder).record(actorId, RenownEventSource.DIRECT_PERSONAL_ACTION)
        Records.general(world, actorId, RecordKind.PERSONAL_APPLIED,
            "${actor.name}의 개인 행동을 마쳤습니다.", mapOf("inputId" to inputId, "requestId" to requestId))
        return TurnOutcome.Applied(inputId, effects)
    }

    companion object { private const val LAST_TURN_KEY = "personalLastTurn" }
}
