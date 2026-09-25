package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldRules
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource

/** Executes one city-owned military action against the same county snapshot used by precheck. */
class CityMilitaryHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: DomesticContext = DomesticContext(),
    private val design: MilitaryDesign = MilitaryDesign.CANON,
) {
    fun handle(inputId: String, actorId: Int, rawJson: String?, requestId: String?, ownerUserId: Int?,
        npcSelected: Boolean = false): TurnOutcome {
        fun reject(reason: MilitaryFailure) = TurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(MilitaryFailure.WRONG_RULE_PROFILE)
        val actor = world.getGeneralById(actorId) ?: return reject(MilitaryFailure.ACTOR_NOT_FOUND)
        val npc = npcSelected && ownerUserId == null && actor.npcState >= 2 && NpcDeploySelector.isUnowned(actor.userId)
        if (!npc && (ownerUserId == null || ownerUserId <= 0 || actor.userId?.toLongOrNull() != ownerUserId.toLong()))
            return TurnOutcome.Rejected(inputId, "FORBIDDEN", "예약한 장수의 소유권이 변경되었습니다.")
        val request = MilitaryInput.parse(actorId, inputId, rawJson) ?: return reject(MilitaryFailure.INVALID_INPUT)
        if (inputId !in MilitaryInput.CITY_INPUT_IDS) return reject(MilitaryFailure.INVALID_INPUT)
        if (design.status != MilitaryDesign.CONFIRMED)
            return TurnOutcome.Rejected(inputId, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val turnToken = actor.turnTime.toString()
        val prior = actor.meta[LAST_TURN_KEY] as? Map<*, *>
        if (prior?.get("turn") == turnToken) {
            if (prior["inputId"] == inputId && prior["requestId"] == requestId)
                return TurnOutcome.Applied(inputId, (prior["effects"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            return reject(MilitaryFailure.ALREADY_PROCESSED)
        }
        val projection = context.projection(world)
        val geographic = FieldRules.assess(FieldRequest(actorId, FieldInput.FARM), projection)
        if (geographic is FieldAssessment.Rejected) return reject(MilitaryFailure.valueOf(geographic.reason.name))
        val county = (geographic as FieldAssessment.Eligible).county
        val city = world.getCityById(county.id) ?: return reject(MilitaryFailure.COUNTY_UNAVAILABLE)
        val condition = try { CityMilitaryState.read(city.meta, city.defence.coerceAtLeast(0)) }
            catch (_: IllegalArgumentException) { return reject(MilitaryFailure.STATE_UNAVAILABLE) }
        val warehouse = try { CountyWarehouse.read(city.meta, city.id) }
            catch (_: IllegalArgumentException) { null }
        val assessment = MilitaryRules.assessCity(request, projection, city.population, city.populationMax,
            condition.troops, condition, warehouse?.stock, design)
        if (assessment is CityMilitaryAssessment.Rejected) return reject(assessment.reason)
        val plan = (assessment as CityMilitaryAssessment.Eligible).plan
        val experience: Int
        val dedication: Int
        try {
            experience = Math.addExact(actor.experience, design.experience)
            dedication = Math.addExact(actor.dedication, design.dedication)
        } catch (_: ArithmeticException) { return reject(MilitaryFailure.STATE_UNAVAILABLE) }
        if (plan.debit != Resources()) {
            val settled = WarehouseSettlement(world, recorder).settle(city.id, city.nationId,
                checkNotNull(warehouse).revision, plan.debit)
            if (settled != WarehouseSettlement.Result.APPLIED) return reject(when (settled) {
                WarehouseSettlement.Result.OWNER_CHANGED -> MilitaryFailure.FOREIGN_COUNTY
                WarehouseSettlement.Result.INSUFFICIENT_STOCK -> MilitaryFailure.INSUFFICIENT_STOCK
                WarehouseSettlement.Result.NOT_READY -> MilitaryFailure.WAREHOUSE_NOT_READY
                else -> MilitaryFailure.STATE_UNAVAILABLE
            })
        }
        val current = checkNotNull(world.getCityById(city.id))
        val afterCondition = plan.condition.copy(troops = plan.troops)
        val after = current.copy(population = plan.population,
            meta = current.meta + (CityMilitaryState.META_KEY to afterCondition.toMetaValue()))
        recorder.diffCity(PerTurnOverlay.toLogicCity(current), PerTurnOverlay.toLogicCity(after))
        world.applyCityDirtyFree(after)
        val effects = buildList {
            if (plan.population != city.population) add("population:${plan.population - city.population}")
            if (plan.troops != condition.troops) add("cityTroops:${plan.troops - condition.troops}")
            if (afterCondition.training != condition.training) add("training:+${afterCondition.training - condition.training}")
            if (afterCondition.morale != condition.morale) add("morale:+${afterCondition.morale - condition.morale}")
            if (plan.debit.grain > 0) add("grain:-${plan.debit.grain}")
            if (plan.debit.money > 0) add("money:-${plan.debit.money}")
            add("experience:+${design.experience}")
            add("dedication:+${design.dedication}")
        }
        val latest = checkNotNull(world.getGeneralById(actorId))
        val stamp = mapOf("turn" to turnToken, "inputId" to inputId, "requestId" to requestId, "effects" to effects)
        val grown = latest.copy(experience = experience, dedication = dedication,
            meta = latest.meta + (LAST_TURN_KEY to stamp))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(latest), PerTurnOverlay.toLogicGeneral(grown))
        world.applyGeneralDirtyFree(grown)
        RenownEventRecorder(world, recorder).record(actorId, RenownEventSource.DIRECT_MILITARY_ACTION)
        Records.general(world, actorId, RecordKind.FIELD_APPLIED,
            "${city.name}에서 군사 행동을 마쳤습니다.", mapOf("inputId" to inputId, "countyId" to city.id, "requestId" to requestId))
        return TurnOutcome.Applied(inputId, effects)
    }

    companion object { private const val LAST_TURN_KEY = "hwihaCityMilitaryLastTurn" }
}
