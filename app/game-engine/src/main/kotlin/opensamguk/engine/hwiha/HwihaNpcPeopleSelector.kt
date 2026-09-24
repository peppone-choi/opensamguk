package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*

/** Unowned lords use their own discovery and captive records; the execution path is shared. */
internal class HwihaNpcPeopleSelector(private val context: HwihaDomesticContext,
    private val design: HwihaPeopleDesign = HwihaPeopleDesign.CANON,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !HwihaPersonalTurn.hasNoInput(reserved) ||
            design.status != HwihaPeopleDesign.CONFIRMED) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!HwihaNpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2 || actor.nationId <= 0 ||
            !runCatching { HwihaLordStatus.read(actor.meta) }.getOrDefault(false) ||
            world.listRetainers().any { it.generalId == actorId } || HwihaCorpsOrder.META_KEY in actor.meta) return reserved
        val deployed = try { HwihaDeploymentState.read(actor.meta)?.corps.orEmpty() }
            catch (_: IllegalArgumentException) { return reserved }
        if (deployed.isNotEmpty()) return reserved
        val state = context.projection(world)
        val here = state.person(actorId)?.node ?: return reserved
        val people = state.peopleAt(here)
        fun eligible(inputId: String, targetId: Int?) =
            catalog[inputId]?.deliveryState?.hasHandler == true &&
                HwihaPeopleRules.assess(HwihaPeopleRequest(actorId, inputId, targetId), state) is HwihaPeopleAssessment.Eligible
        val captive = people.firstOrNull { target ->
            (target.meta["hwihaCaptive"] as? Map<*, *>)?.get("captorGeneralId") == actorId &&
                eligible(HwihaPeopleInput.PERSUADE_CAPTIVE, target.id)
        }
        if (captive != null) return order(HwihaPeopleInput.PERSUADE_CAPTIVE, actorId, captive.id)
        val known = try { HwihaTalentDiscovery.read(actor.meta) } catch (_: IllegalArgumentException) { return reserved }
        val recruit = people.firstOrNull { it.id in known && eligible(HwihaPeopleInput.EMPLOY, it.id) }
        if (recruit != null) return order(HwihaPeopleInput.EMPLOY, actorId, recruit.id)
        if (eligible(HwihaPeopleInput.SEARCH, null)) return order(HwihaPeopleInput.SEARCH, actorId, null)
        return reserved
    }

    private fun order(inputId: String, actorId: Int, targetId: Int?) = ReservedTurn(inputId,
        HwihaPeopleInput.canonicalJson(HwihaPeopleRequest(actorId, inputId, targetId)),
        brief = inputId, rowExists = false)
}
