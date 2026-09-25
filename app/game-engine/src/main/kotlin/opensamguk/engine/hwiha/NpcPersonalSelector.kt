package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*

/** NPCs use the same current-condition rules as player reservations. */
internal class NpcPersonalSelector(private val context: DomesticContext,
    private val design: PersonalDesign = PersonalDesign.CANON,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !PersonalTurn.hasNoInput(reserved) ||
            design.status != PersonalDesign.CONFIRMED) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!NpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2 ||
            world.listRetainers().any { it.generalId == actorId } || CorpsOrder.META_KEY in actor.meta)
            return reserved
        val state = context.projection(world)
        fun eligible(request: PersonalRequest) =
            catalog[request.inputId]?.deliveryState?.hasHandler == true &&
                PersonalRules.assess(request, state) is PersonalAssessment.Eligible
        val heal = PersonalRequest(actorId, PersonalInput.RECUPERATE)
        if (eligible(heal)) return order(heal)
        val train = TrainingStat.entries.filter { eligible(PersonalRequest(actorId,
            PersonalInput.SELF_TRAIN, it)) }.minWithOrNull(compareBy({ stat ->
                when (stat) {
                    TrainingStat.LEADERSHIP -> actor.stats.leadership
                    TrainingStat.STRENGTH -> actor.stats.strength
                    TrainingStat.INTELLIGENCE -> actor.stats.intelligence
                    TrainingStat.POLITICS -> actor.stats.politics
                    TrainingStat.CHARM -> actor.stats.charm
                }
            }, { it.ordinal }))
        if (train != null) return order(PersonalRequest(actorId, PersonalInput.SELF_TRAIN, train))
        val travel = PersonalRequest(actorId, PersonalInput.TRAVEL)
        return if (eligible(travel)) order(travel) else reserved
    }

    private fun order(request: PersonalRequest) = ReservedTurn(request.inputId,
        PersonalInput.canonicalJson(request), brief = request.inputId, rowExists = false)
}
