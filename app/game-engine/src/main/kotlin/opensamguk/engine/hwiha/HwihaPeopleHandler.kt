package opensamguk.engine.hwiha

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.*
import opensamguk.logic.domestic.PlacementMarch
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.retainer.RetainerRules

/** Local talent search and consent or resistance adjudication use one personal-turn RNG. */
class HwihaPeopleHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
    private val hiddenSeed: String,
    private val design: PeopleDesign = PeopleDesign.CANON,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val rngFactory: (String) -> RandUtil = { RandUtil(LiteHashDrbg(it)) },
) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(reason: PeopleFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(PeopleFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(PeopleFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return HwihaTurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = PeopleInput.parse(actorId, inputId, rawJson) ?: return reject(PeopleFailure.INVALID_INPUT)
        if (catalog[inputId]?.deliveryState?.hasHandler != true)
            return HwihaTurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        if (design.status != PeopleDesign.CONFIRMED)
            return HwihaTurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val prior = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (prior?.get("turn") == turnToken) {
            if (prior["inputId"] == inputId && prior["requestId"] == requestId)
                return HwihaTurnOutcome.Applied(inputId, (prior["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(PeopleFailure.ALREADY_PROCESSED)
        }
        val assessment = PeopleRules.assess(request, context.projection(world))
        if (assessment is PeopleAssessment.Rejected) return reject(assessment.reason)
        val ready = assessment as PeopleAssessment.Eligible
        val experience: Int
        val dedication: Int
        try {
            experience = Math.addExact(actor.experience, design.experience)
            dedication = Math.addExact(actor.dedication, design.dedication)
        } catch (_: ArithmeticException) { return reject(PeopleFailure.STATE_UNAVAILABLE) }
        val now = world.getState()
        val rng by lazy { rngFactory(world.personalTurnSeed(hiddenSeed, "generalCommand", now.currentYear,
            now.currentMonth, actorId, inputId)) }
        val effects = mutableListOf<String>()
        var recordKind = RecordKind.PEOPLE_SEARCHED
        var recordText = "현재 지역에서 인재를 탐색했습니다."
        if (inputId == PeopleInput.SEARCH) {
            val remaining = ready.candidateIds.toMutableList()
            repeat(minOf(design.searchDiscoverCount, remaining.size)) {
                val index = if (remaining.size == 1 || remaining.size <= design.searchDiscoverCount) 0
                    else rng.nextInt(0, remaining.size)
                val found = remaining.removeAt(index)
                effects += "discoveredGeneralId:$found"
            }
        } else {
            val target = checkNotNull(ready.target)
            val chance = design.acceptancePercent(ready.actor.charm, target.charm,
                inputId == PeopleInput.PERSUADE_CAPTIVE)
            val accepted = rng.nextInt(0, 100) < chance
            if (accepted) {
                val liveTarget = world.getGeneralById(target.id) ?: return reject(PeopleFailure.TARGET_UNAVAILABLE)
                if (actor.nationId > 0 && world.getNationById(actor.nationId) == null)
                    return reject(PeopleFailure.STATE_UNAVAILABLE)
                if (liveTarget.nationId > 0 && world.getNationById(liveTarget.nationId) == null)
                    return reject(PeopleFailure.STATE_UNAVAILABLE)
                val formerNationId = liveTarget.nationId
                val joining = ready.joiningGeneralIds.map { id ->
                    world.getGeneralById(id) ?: return reject(PeopleFailure.STATE_UNAVAILABLE)
                }
                if (joining.isEmpty() || joining.first().id != target.id)
                    return reject(PeopleFailure.STATE_UNAVAILABLE)
                val targetMeta = liveTarget.meta - HwihaEncounterResolver.CAPTIVE_KEY -
                    PlacementState.META_KEY - PlacementMarch.META_KEY -
                    CountyAssignment.META_KEY - CorpsOrder.META_KEY - CorpsMarchState.META_KEY
                for (before in joining) {
                    val joined = before.copy(nationId = actor.nationId,
                        officerLevel = if (before.id == target.id) 0 else before.officerLevel,
                        meta = if (before.id == target.id) LordStatus.afterEnlistment(targetMeta) else before.meta)
                    recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(joined))
                    world.applyGeneralDirtyFree(joined)
                }
                val oldCard = world.listRetainers().singleOrNull { it.generalId == target.id }
                val card = if (oldCard == null) Retainer(world.allocateRetainerId(), actorId,
                    RetainerRules.ORIGIN_EXISTING, target.id, target.name, RetainerRules.RELATION_GUEST,
                    RetainerRules.ROLE_NONE, true, RetainerRules.RELEASE_MUTUAL, 50, RetainerRules.TASK_NONE)
                    .also(world::createRetainer)
                else {
                    for (unit in world.listBugoks().filter { it.commanderRetainerId == oldCard.id &&
                        it.masterGeneralId != actorId }) world.updateBugok(unit.copy(commanderRetainerId = null))
                    oldCard.copy(masterGeneralId = actorId, relation = RetainerRules.RELATION_GUEST,
                        role = RetainerRules.ROLE_NONE, hasOwnBugok = true,
                        releasePolicy = RetainerRules.RELEASE_MUTUAL, loyalty = 50,
                        task = RetainerRules.TASK_NONE).also(world::updateRetainer)
                }
                for (nationId in setOf(formerNationId, actor.nationId).filter { it > 0 }) {
                    val before = world.getNationById(nationId) ?: continue
                    val count = world.listGenerals().count { it.nationId == nationId && it.npcState != 5 }
                    val after = before.copy(meta = before.meta + ("gennum" to count))
                    recorder.diffNation(PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(after))
                    world.applyNationDirtyFree(after)
                }
                effects += "retainerId:${card.id}"
                effects += "joinedGeneralId:${target.id}"
                recordKind = RecordKind.PEOPLE_JOINED
                recordText = "${JosaUtil.put(target.name, "이")} 동의하여 휘하에 들어왔습니다."
                HwihaRecords.general(world, target.id, RecordKind.RETAINER_JOINED,
                    "${actor.name}의 휘하에 들어갔습니다.", mapOf("masterGeneralId" to actorId, "retainerId" to card.id))
            } else {
                effects += "resistedGeneralId:${target.id}"
                recordKind = RecordKind.PEOPLE_RESISTED
                recordText = "${JosaUtil.put(target.name, "이")} 제안을 거절했습니다."
            }
        }
        effects += "experience:+${design.experience}"
        effects += "dedication:+${design.dedication}"
        val latest = checkNotNull(world.getGeneralById(actorId))
        val discovered = if (inputId == PeopleInput.SEARCH) effects.filter { it.startsWith("discoveredGeneralId:") }
            .fold(latest.meta) { meta, value -> TalentDiscovery.add(meta, value.substringAfter(':').toInt()) }
            else latest.meta
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val grown = latest.copy(experience = experience, dedication = dedication,
            meta = discovered + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(latest), PerTurnOverlay.toLogicGeneral(grown))
        world.applyGeneralDirtyFree(grown)
        HwihaRenownEventRecorder(world, recorder).record(actorId, RenownEventSource.DIRECT_PEOPLE_ACTION)
        HwihaRecords.general(world, actorId, recordKind, recordText,
            mapOf("inputId" to inputId, "targetGeneralId" to request.targetGeneralId, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(inputId, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaPeopleLastTurn" }
}
