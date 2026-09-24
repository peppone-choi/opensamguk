package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.*

/** Resolves one field-phase personal action at the general's current land position. */
class HwihaPersonalHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
    private val design: HwihaPersonalDesign = HwihaPersonalDesign.CANON,
) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: HwihaPersonalFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaPersonalFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaPersonalFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = HwihaPersonalInput.parse(actorId, inputId, rawJson)
            ?: return reject(HwihaPersonalFailure.INVALID_INPUT)
        if (design.status != HwihaPersonalDesign.CONFIRMED)
            return HwihaTurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val prior = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (prior?.get("turn") == turnToken) {
            if (prior["inputId"] == inputId && prior["requestId"] == requestId)
                return HwihaTurnOutcome.Applied(inputId, (prior["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaPersonalFailure.ALREADY_PROCESSED)
        }
        val check = HwihaPersonalRules.assess(request, context.projection(world))
        if (check is HwihaPersonalAssessment.Rejected) return reject(check.reason)
        val condition = (check as HwihaPersonalAssessment.Eligible).condition
        val nextExperience: Int
        val nextDedication: Int
        try {
            nextExperience = Math.addExact(actor.experience, design.experiencePerAction)
            nextDedication = Math.addExact(actor.dedication, design.dedicationPerAction)
        } catch (_: ArithmeticException) { return reject(HwihaPersonalFailure.STATE_UNAVAILABLE) }
        val effects = mutableListOf<String>()
        val next = when (inputId) {
            HwihaPersonalInput.TRAVEL -> actor
            HwihaPersonalInput.SELF_TRAIN -> {
                val stat = checkNotNull(request.trainingStat)
                val grown = when (stat) {
                    HwihaTrainingStat.LEADERSHIP -> actor.stats.copy(leadership = (actor.stats.leadership + design.trainingStatGain).coerceAtMost(100))
                    HwihaTrainingStat.STRENGTH -> actor.stats.copy(strength = (actor.stats.strength + design.trainingStatGain).coerceAtMost(100))
                    HwihaTrainingStat.INTELLIGENCE -> actor.stats.copy(intelligence = (actor.stats.intelligence + design.trainingStatGain).coerceAtMost(100))
                    HwihaTrainingStat.POLITICS -> actor.stats.copy(politics = (actor.stats.politics + design.trainingStatGain).coerceAtMost(100))
                    HwihaTrainingStat.CHARM -> actor.stats.copy(charm = (actor.stats.charm + design.trainingStatGain).coerceAtMost(100))
                }
                val after = condition.copy(fatigue = (condition.fatigue + design.trainingFatigueGain).coerceAtMost(100))
                effects += "${stat.wireName}:+${design.trainingStatGain}"
                effects += "fatigue:+${after.fatigue - condition.fatigue}"
                actor.copy(stats = grown, meta = actor.meta + (HwihaPersonalTravelCondition.META_KEY to after.toMetaValue()))
            }
            HwihaPersonalInput.RECUPERATE -> {
                val injury = (actor.injury - design.recuperationInjuryRecovery).coerceAtLeast(0)
                val after = condition.copy(fatigue = (condition.fatigue - design.recuperationFatigueRecovery).coerceAtLeast(0))
                effects += "injury:${injury - actor.injury}"
                effects += "fatigue:${after.fatigue - condition.fatigue}"
                actor.copy(injury = injury, meta = actor.meta + (HwihaPersonalTravelCondition.META_KEY to after.toMetaValue()))
            }
            else -> return reject(HwihaPersonalFailure.INVALID_INPUT)
        }
        effects += "experience:+${design.experiencePerAction}"
        effects += "dedication:+${design.dedicationPerAction}"
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val grown = next.copy(experience = nextExperience, dedication = nextDedication,
            meta = next.meta + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(grown))
        world.applyGeneralDirtyFree(grown)
        HwihaRenownEventRecorder(world, recorder).record(actorId, HwihaRenownEventSource.DIRECT_PERSONAL_ACTION)
        HwihaRecords.general(world, actorId, HwihaRecordKind.PERSONAL_APPLIED,
            "${actor.name}의 개인 행동을 마쳤습니다.", mapOf("inputId" to inputId, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(inputId, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaPersonalLastTurn" }
}
