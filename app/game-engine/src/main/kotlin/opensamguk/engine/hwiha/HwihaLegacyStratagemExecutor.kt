package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*

internal data class HwihaQueuedLegacyStratagem(val requestId: String, val ownerUserId: Int,
    val inputId: String, val argJson: String) {
    fun toMetaValue(): Map<String, Any?> = mapOf("version" to 1, "requestId" to requestId,
        "ownerUserId" to ownerUserId, "inputId" to inputId, "argJson" to argJson)
    companion object {
        const val META_KEY = "hwihaQueuedLegacyStratagem"
        fun read(meta: Map<String, Any?>): HwihaQueuedLegacyStratagem? {
            val raw = meta[META_KEY] ?: return null
            val row = raw as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "requestId", "ownerUserId", "inputId", "argJson") && row["version"] == 1)
            val requestId = row["requestId"] as? String ?: invalid()
            val owner = row["ownerUserId"] as? Int ?: invalid()
            val inputId = row["inputId"] as? String ?: invalid()
            val json = row["argJson"] as? String ?: invalid()
            require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && owner > 0 &&
                inputId in HwihaLegacyStratagemInput.INPUT_IDS && json.length <= 4096)
            return HwihaQueuedLegacyStratagem(requestId, owner, inputId, json)
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("invalid queued stratagem")
    }
}

internal class HwihaLegacyStratagemExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext) {
    fun assess(request: HwihaLegacyStratagemInput.Request) =
        HwihaLegacyStratagemRules.assess(request, context.projection(world))

    fun execute(request: HwihaLegacyStratagemInput.Request): HwihaLegacyStratagemAssessment.Rejected? {
        val assessed = assess(request)
        if (assessed is HwihaLegacyStratagemAssessment.Rejected) return assessed
        val ready = (assessed as HwihaLegacyStratagemAssessment.Eligible).ready
        val beforeSource = world.getCityById(ready.source.id) ?: return reject(HwihaLegacyStratagemFailure.SOURCE_UNAVAILABLE)
        val beforeTarget = ready.target?.let { world.getCityById(it.id) }
        if (ready.target != null && beforeTarget == null) return reject(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
        val actor = world.getGeneralById(request.actorId) ?: return reject(HwihaLegacyStratagemFailure.ACTOR_NOT_FOUND)
        var source = beforeSource
        var target = beforeTarget
        var sourceStock = ready.warehouse.stock.debit(ready.cost)!!
        var targetStock = ready.targetWarehouse?.stock
        val transfer = when (request.inputId) {
            "stratagem.steal" -> Resources(money = 100)
            "stratagem.raid" -> Resources(grain = 300)
            else -> null
        }
        if (transfer != null) {
            targetStock = targetStock!!.debit(transfer) ?: return reject(HwihaLegacyStratagemFailure.INSUFFICIENT_STOCK)
            sourceStock = try { sourceStock.credit(transfer) }
                catch (_: ArithmeticException) { return reject(HwihaLegacyStratagemFailure.STOCK_OVERFLOW) }
        }
        when (request.inputId) {
            "stratagem.rumor" -> target = target!!.copy(meta = target.meta +
                ("trust" to ((target.meta["trust"] as? Number)?.toDouble() ?: 50.0).minus(2.0).coerceAtLeast(0.0)))
            "stratagem.sabotage" -> target = target!!.copy(defence = (target.defence - 50).coerceAtLeast(0))
            "stratagem.fire" -> target = target!!.copy(wall = (target.wall - 50).coerceAtLeast(0))
            "stratagem.flood" -> target = target!!.copy(agriculture = (target.agriculture - 50).coerceAtLeast(0),
                population = (target.population - 50).coerceAtLeast(0))
            "stratagem.falseReport" -> {
                val military = HwihaCityMilitaryState.read(target!!.meta)
                target = target.copy(meta = target.meta + (HwihaCityMilitaryState.META_KEY to
                    military.copy(morale = (military.morale - 10).coerceAtLeast(0)).toMetaValue()))
            }
            "stratagem.mobilizePeople" -> source = source.copy(population = (source.population - 100).coerceAtLeast(0),
                defence = (source.defence + 50).coerceAtMost(source.defenceMax))
            "stratagem.raiseMilitia" -> {
                val military = HwihaCityMilitaryState.read(source.meta)
                source = source.copy(population = (source.population - 50).coerceAtLeast(0),
                    meta = source.meta + (HwihaCityMilitaryState.META_KEY to military.copy(
                        troops = Math.addExact(military.troops, 50),
                        morale = (military.morale + 10).coerceAtMost(100)).toMetaValue()))
            }
            "stratagem.reciprocity" -> {
                source = source.copy(defence = (source.defence - 50).coerceAtLeast(0))
                target = target!!.copy(defence = (target.defence - 50).coerceAtLeast(0))
            }
            "stratagem.lastStand" -> {
                val condition = HwihaPersonalTravelCondition.read(actor.meta) ?: HwihaPersonalTravelCondition.INITIAL
                val next = actor.copy(meta = actor.meta + (HwihaPersonalTravelCondition.META_KEY to condition.copy(
                    fatigue = (condition.fatigue + 10).coerceAtMost(100),
                    morale = (condition.morale + 20).coerceAtMost(100)).toMetaValue()))
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(next))
                world.applyGeneralDirtyFree(next)
            }
            HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> {
                val a = ready.firstNation!!.id; val b = ready.secondNation!!.id
                for ((from, to) in listOf(a to b, b to a)) {
                    val pre = world.getDiplomacy(from, to) ?: return reject(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
                    val next = world.updateDiplomacy(from, to, DiplomacyState.WAR, 3)
                        ?: return reject(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE)
                    recorder.diffDiplomacy(pre, next)
                }
            }
            "stratagem.steal", "stratagem.raid" -> Unit
            else -> return reject(HwihaLegacyStratagemFailure.INVALID_INPUT)
        }
        source = source.copy(meta = source.meta + (CountyWarehouse.META_KEY to
            ready.warehouse.replace(sourceStock).toMetaValue()))
        if (targetStock != null) target = target!!.copy(meta = target.meta +
            (CountyWarehouse.META_KEY to ready.targetWarehouse!!.replace(targetStock).toMetaValue()))
        updateCity(beforeSource, source)
        if (beforeTarget != null && target != null) updateCity(beforeTarget, target)
        val current = world.getGeneralById(request.actorId) ?: return reject(HwihaLegacyStratagemFailure.ACTOR_NOT_FOUND)
        world.updateGeneralMeta(recorder, current, current.meta + (HwihaLegacyStratagemStock.META_KEY to
            ready.cardStock.consume(request.inputId).toMetaValue()))
        HwihaRecords.general(world, request.actorId, HwihaRecordKind.PERSONAL_APPLIED,
            "${ready.actor.name}의 계책을 펼쳤습니다.", mapOf("inputId" to request.inputId))
        return null
    }

    private fun updateCity(before: City, after: City) {
        if (before == after) return
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
        world.applyCityDirtyFree(after)
    }
    private fun reject(reason: HwihaLegacyStratagemFailure) = HwihaLegacyStratagemAssessment.Rejected(reason)
}
