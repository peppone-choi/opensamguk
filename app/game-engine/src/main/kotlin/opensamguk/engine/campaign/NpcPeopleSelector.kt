package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*

/** Unowned lords use their own discovery and captive records; the execution path is shared. */
internal class NpcPeopleSelector(private val context: DomesticContext,
    private val design: PeopleDesign = PeopleDesign.CANON,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !PersonalTurn.hasNoInput(reserved) ||
            design.status != PeopleDesign.CONFIRMED) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!NpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2 || actor.nationId <= 0 ||
            !runCatching { LordStatus.read(actor.meta) }.getOrDefault(false) ||
            world.listRetainers().any { it.generalId == actorId } || CorpsOrder.META_KEY in actor.meta) return reserved
        val deployed = try { DeploymentState.read(actor.meta)?.corps.orEmpty() }
            catch (_: IllegalArgumentException) { return reserved }
        if (deployed.isNotEmpty()) return reserved
        val state = context.projection(world)
        val here = state.person(actorId)?.node ?: return reserved
        val people = state.peopleAt(here)
        fun eligible(inputId: String, targetId: Int?) =
            catalog[inputId]?.deliveryState?.hasHandler == true &&
                PeopleRules.assess(PeopleRequest(actorId, inputId, targetId), state) is PeopleAssessment.Eligible
        val captive = people.firstOrNull { target ->
            (target.meta["hwihaCaptive"] as? Map<*, *>)?.get("captorGeneralId") == actorId &&
                eligible(PeopleInput.PERSUADE_CAPTIVE, target.id)
        }
        if (captive != null) return order(PeopleInput.PERSUADE_CAPTIVE, actorId, captive.id)
        val known = try { TalentDiscovery.read(actor.meta) } catch (_: IllegalArgumentException) { return reserved }
        val recruit = people.firstOrNull { it.id in known && eligible(PeopleInput.EMPLOY, it.id) }
        if (recruit != null) return order(PeopleInput.EMPLOY, actorId, recruit.id)
        if (eligible(PeopleInput.SEARCH, null)) return order(PeopleInput.SEARCH, actorId, null)
        return reserved
    }

    private fun order(inputId: String, actorId: Int, targetId: Int?) = ReservedTurn(inputId,
        PeopleInput.canonicalJson(PeopleRequest(actorId, inputId, targetId)),
        brief = inputId, rowExists = false)
}
