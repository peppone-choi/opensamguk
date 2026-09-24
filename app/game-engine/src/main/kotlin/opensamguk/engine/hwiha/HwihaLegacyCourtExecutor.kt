package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** Queued on submission; replayed exactly once at the issuer's next turn. */
internal data class HwihaQueuedLegacyCourt(val requestId: String, val ownerUserId: Int, val inputId: String, val argJson: String) {
    fun toMetaValue(): Map<String, Any?> = mapOf("version" to 1, "requestId" to requestId,
        "ownerUserId" to ownerUserId, "inputId" to inputId, "argJson" to argJson)
    companion object {
        const val META_KEY = "hwihaQueuedLegacyCourt"
        fun read(meta: Map<String, Any?>): HwihaQueuedLegacyCourt? {
            val value = meta[META_KEY] ?: return null
            val row = value as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "requestId", "ownerUserId", "inputId", "argJson") && row["version"] == 1)
            val requestId = row["requestId"] as? String ?: invalid()
            val owner = row["ownerUserId"] as? Int ?: invalid()
            val inputId = row["inputId"] as? String ?: invalid()
            val json = row["argJson"] as? String ?: invalid()
            require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && owner > 0 && inputId in HwihaLegacyCourtInput.INPUT_IDS)
            require(json.length <= 4096)
            return HwihaQueuedLegacyCourt(requestId, owner, inputId, json)
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("invalid queued legacy court input")
    }
}

/** One executor and one assessment for every court legacy mode. */
internal class HwihaLegacyCourtExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext) {
    fun assess(actorId: Int, inputId: String, json: String) =
        HwihaLegacyCourtRules.assess(actorId, inputId, json, context.projection(world))

    fun execute(actorId: Int, inputId: String, json: String): HwihaLegacyCourtAssessment.Rejected? {
        val result = assess(actorId, inputId, json)
        if (result is HwihaLegacyCourtAssessment.Rejected) return result
        val ready = (result as HwihaLegacyCourtAssessment.Eligible).ready
        val nation = world.getNationById(ready.nation.id) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
        when (inputId) {
            HwihaCourtExpansionInput.RELEASE_CORPS -> {
                val corps = ready.corps!!
                val owner = world.getGeneralById(corps.ownerGeneralId) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                val deployment = HwihaDeploymentState.read(owner.meta) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                val remaining = deployment.corps.filterNot { it.orderId == corps.orderId }
                world.updateGeneralMeta(recorder, owner, owner.meta.withKey(HwihaDeploymentState.META_KEY,
                    remaining.takeIf { it.isNotEmpty() }?.let { HwihaDeploymentState(it).toMetaValue() }))
                val commander = world.getGeneralById(corps.commanderGeneralId) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                world.updateGeneralMeta(recorder, commander,
                    commander.meta - HwihaCorpsOrder.META_KEY - HwihaCorpsMarchState.META_KEY)
            }
            HwihaCourtExpansionInput.ABANDON_COUNTY -> {
                val county = world.getCityById(ready.county!!.id) ?: return reject(HwihaLegacyCourtFailure.COUNTY_UNAVAILABLE)
                val next = county.copy(nationId = 0)
                recorder.diffCity(PerTurnOverlay.toLogicCity(county), PerTurnOverlay.toLogicCity(next))
                world.applyCityDirtyFree(next)
            }
            HwihaCourtExpansionInput.MOVE_CAPITAL -> {
                val next = nation.copy(capitalCityId = ready.county!!.id)
                recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(next))
                world.applyNationDirtyFree(next)
            }
            HwihaLegacyCourtInput.INSTITUTION -> {
                val stock = ready.sourceStock!!.debit(with(HwihaTransferRules) { HwihaTransferResource.MONEY.amount(100) })!!
                val next = nation.copy(gold = HwihaPortableStock.checkedColumn(stock.money), tech = nation.tech + 10.0,
                    meta = HwihaPortableStock.withStock(nation.meta, stock))
                recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(next))
                world.applyNationDirtyFree(next)
            }
            in HwihaCourtResourceInput.INPUT_IDS -> {
                val request = HwihaCourtResourceInput.parse(actorId, inputId, json)!!
                val delta = with(HwihaTransferRules) { request.resource.amount(request.amount.toLong()) }
                val source = ready.sourceStock!!.debit(delta)!!
                val destination = ready.destinationStock!!.credit(delta)
                if (inputId == HwihaCourtResourceInput.CONFISCATE) {
                    val person = world.getGeneralById(ready.targetPerson!!.id) ?: return reject(HwihaLegacyCourtFailure.TARGET_UNAVAILABLE)
                    updatePerson(person, source)
                    updateNation(nation, destination)
                } else {
                    updateNation(nation, source)
                    val target = world.getNationById(ready.targetNation!!.id) ?: return reject(HwihaLegacyCourtFailure.TARGET_UNAVAILABLE)
                    updateNation(target, destination)
                }
            }
            in HwihaDiplomacyInput.INPUT_IDS -> {
                val other = ready.targetNation!!.id
                val forward = world.getDiplomacy(nation.id, other) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                val backward = world.getDiplomacy(other, nation.id) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                val nextState = when (inputId) {
                    HwihaDiplomacyInput.NON_AGGRESSION -> 7
                    HwihaDiplomacyInput.DECLARE_WAR -> 1
                    HwihaDiplomacyInput.OFFER_PEACE, HwihaDiplomacyInput.BREAK_NON_AGGRESSION -> 0
                    else -> return reject(HwihaLegacyCourtFailure.INVALID_INPUT)
                }
                for (pre in listOf(forward, backward)) {
                    val next = world.updateDiplomacy(pre.fromNationId, pre.toNationId, nextState,
                        if (nextState == 7) 12 else 0) ?: return reject(HwihaLegacyCourtFailure.STATE_UNAVAILABLE)
                    recorder.diffDiplomacy(pre, next)
                }
            }
            else -> return reject(HwihaLegacyCourtFailure.INVALID_INPUT)
        }
        HwihaRecords.general(world, actorId, HwihaRecordKind.PERSONAL_APPLIED,
            "${ready.actor.name}의 조정 결정을 실행했습니다.", mapOf("inputId" to inputId))
        return null
    }

    private fun updatePerson(before: TurnGeneral, stock: opensamguk.logic.economy.HwihaResources) {
        val next = before.copy(gold = HwihaPortableStock.checkedColumn(stock.money),
            rice = HwihaPortableStock.checkedColumn(stock.grain), meta = HwihaPortableStock.withStock(before.meta, stock))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
    }
    private fun updateNation(before: Nation, stock: opensamguk.logic.economy.HwihaResources) {
        val next = before.copy(gold = HwihaPortableStock.checkedColumn(stock.money),
            rice = HwihaPortableStock.checkedColumn(stock.grain), meta = HwihaPortableStock.withStock(before.meta, stock))
        recorder.diffNation(PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(next))
        world.applyNationDirtyFree(next)
    }
    private fun reject(reason: HwihaLegacyCourtFailure) = HwihaLegacyCourtAssessment.Rejected(reason)
}
