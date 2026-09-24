package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*

/** Applies one direct county action after the personal movement stage. */
class HwihaFieldHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): HwihaTurnOutcome {
        fun reject(code: String, reason: String) = HwihaTurnOutcome.Rejected(inputId, code, reason)
        fun reject(reason: HwihaFieldFailure) = reject(reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(HwihaFieldFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(HwihaFieldFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && HwihaNpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return reject("FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = HwihaFieldInput.parse(actorId, inputId, rawJson) ?: return reject(HwihaFieldFailure.INVALID_INPUT)
        val turnToken = actor.turnTime.toString()
        val previous = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (previous?.get("turn") == turnToken) {
            if (previous["inputId"] == inputId && previous["requestId"] == requestId)
                return HwihaTurnOutcome.Applied(inputId, (previous["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(HwihaFieldFailure.ALREADY_PROCESSED)
        }
        val projection = context.projection(world)
        val assessment = HwihaFieldRules.assess(request, projection)
        if (assessment is HwihaFieldAssessment.Rejected) return reject(assessment.reason)
        val eligible = assessment as HwihaFieldAssessment.Eligible
        val design = context.design
        if (design.directActionStatus != HwihaDomesticDesign.CONFIRMED)
            return reject("NOT_DELIVERED", "현장 행동의 효과 수치가 확정되지 않았습니다.")
        val city = world.getCityById(eligible.county.id) ?: return reject(HwihaFieldFailure.COUNTY_UNAVAILABLE)
        val levels = HwihaDomesticCountyEffects.levelsOf(city)
        val warehouse = try { HwihaCountyWarehouse.read(city.meta, city.id) }
            catch (_: IllegalArgumentException) { null }
        val economy = HwihaFieldRules.assessEconomy(inputId, eligible.person, city.id, levels, warehouse?.stock, design)
        if (economy is HwihaFieldEconomyAssessment.Rejected) return reject(economy.reason)
        val effect = (economy as HwihaFieldEconomyAssessment.Eligible).outcome
        val growth = design.directActions.getValue(inputId)
        val newExperience: Int
        val newDedication: Int
        try {
            newExperience = Math.addExact(actor.experience, growth.experience)
            newDedication = Math.addExact(actor.dedication, growth.dedication)
        } catch (_: ArithmeticException) { return reject(HwihaFieldFailure.STATE_UNAVAILABLE) }
        if (effect.debit != HwihaResources() || effect.credit != HwihaResources()) {
            val settled = HwihaWarehouseSettlement(world, recorder).settle(city.id, city.nationId, checkNotNull(warehouse).revision,
                effect.debit, effect.credit)
            if (settled != HwihaWarehouseSettlement.Result.APPLIED) return reject(when (settled) {
                HwihaWarehouseSettlement.Result.WRONG_RULE_PROFILE -> HwihaFieldFailure.WRONG_RULE_PROFILE
                HwihaWarehouseSettlement.Result.NOT_COUNTY -> HwihaFieldFailure.COUNTY_UNAVAILABLE
                HwihaWarehouseSettlement.Result.OWNER_CHANGED -> HwihaFieldFailure.FOREIGN_COUNTY
                HwihaWarehouseSettlement.Result.NOT_READY -> HwihaFieldFailure.WAREHOUSE_NOT_READY
                HwihaWarehouseSettlement.Result.INSUFFICIENT_STOCK -> HwihaFieldFailure.INSUFFICIENT_STOCK
                else -> HwihaFieldFailure.STATE_UNAVAILABLE
            })
        }
        val current = checkNotNull(world.getCityById(city.id))
        val trust = ReservedTurnHandler.materializeMariaDbFloat(effect.levels.trust)
        val meta = if (trust == HwihaDomesticCountyEffects.trustOf(current)) current.meta else current.meta.withKey("trust", trust)
        val next = current.copy(population = effect.levels.population, agriculture = effect.levels.agriculture,
            commerce = effect.levels.commerce, security = effect.levels.security, defence = effect.levels.defence,
            wall = effect.levels.wall, meta = meta)
        if (next != current) {
            recorder.diffCity(PerTurnOverlay.toLogicCity(current), PerTurnOverlay.toLogicCity(next))
            checkNotNull(world.applyCityDirtyFree(next))
        }
        val effects = buildList {
            fun changed(name: String, before: Number, after: Number) {
                if (before.toDouble() != after.toDouble()) add("$name:${after.toDouble() - before.toDouble()}")
            }
            changed("population", levels.population, effect.levels.population)
            changed("agriculture", levels.agriculture, effect.levels.agriculture)
            changed("commerce", levels.commerce, effect.levels.commerce)
            changed("security", levels.security, effect.levels.security)
            changed("trust", levels.trust, effect.levels.trust)
            changed("defence", levels.defence, effect.levels.defence)
            changed("wall", levels.wall, effect.levels.wall)
            for ((name, amount) in listOf("money" to effect.debit.money, "grain" to effect.debit.grain,
                "iron" to effect.debit.iron, "timber" to effect.debit.timber, "horses" to effect.debit.horses))
                if (amount > 0) add("$name:-$amount")
            add("experience:+${growth.experience}")
            add("dedication:+${growth.dedication}")
        }
        val latest = checkNotNull(world.getGeneralById(actorId))
        val record = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val grown = latest.copy(experience = newExperience, dedication = newDedication,
            meta = latest.meta.withKey(LAST_TURN_KEY, record))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(latest), PerTurnOverlay.toLogicGeneral(grown))
        world.applyGeneralDirtyFree(grown)
        HwihaRenownEventRecorder(world, recorder).record(actorId, HwihaRenownEventSource.DIRECT_COUNTY_ACTION)
        HwihaRecords.general(world, actorId, HwihaRecordKind.FIELD_APPLIED, "${city.name}에서 현장 행동을 마쳤습니다.",
            linkedMapOf("inputId" to inputId, "countyId" to city.id, "requestId" to requestId))
        return HwihaTurnOutcome.Applied(inputId, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaFieldLastTurn" }
}
