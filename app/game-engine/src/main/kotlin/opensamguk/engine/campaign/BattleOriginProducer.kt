package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.battle.realtime.BattleOwnerNativeGeneral
import opensamguk.logic.battle.realtime.CampaignBattleOriginDraft
import opensamguk.logic.battle.realtime.CampaignBattleOwnerClassifier
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.EncounterForces
import opensamguk.logic.input.EncounterRelations

/** Inert origin draft; existing automatic battle settlement is deliberately unchanged. */
object BattleOriginProducer {
    data class Result(
        val draft: CampaignBattleOriginDraft? = null,
        val reason: String? = null,
        val detail: String? = null,
        val ownerGeneralId: Int? = null,
    )

    fun draft(
        encounter: CorpsEncounter,
        forces: EncounterForces,
        relations: EncounterRelations?,
        world: InMemoryTurnWorld,
        tilesHash: String,
    ): Result {
        if (encounter.defenders.map { it.nationId }.distinct().size != 1)
            return Result(reason = "DEFERRED_MULTI_FACTION")
        if (relations == null) return Result(reason = "RELATIONS_MISSING")
        forces.requireBinding(encounter)
        relations.requireBinding(encounter)
        val ownerIds = (listOf(encounter.attacker) + encounter.defenders).mapTo(linkedSetOf()) { it.ownerGeneralId }
        val native = world.listGenerals().map { BattleOwnerNativeGeneral(world.worldId.value, it.id, it.userId, it.npcState) }
        val classification = CampaignBattleOwnerClassifier.classify(world.worldId.value, ownerIds, native)
        if (classification is CampaignBattleOwnerClassifier.Rejected)
            return Result(reason = "OWNER_AUTHORITY_INVALID", detail = classification.detail,
                ownerGeneralId = classification.ownerGeneralId)
        return Result(draft = CampaignBattleOriginDraft(world.worldId.value, encounter, forces, relations, tilesHash))
    }
}
