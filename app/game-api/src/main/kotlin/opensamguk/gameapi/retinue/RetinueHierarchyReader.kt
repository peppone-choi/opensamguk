package opensamguk.gameapi.retinue

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.campaignReadGate
import opensamguk.gameapi.read.ownedCampaignGeneral
import opensamguk.logic.retainer.RetinueHierarchy
import opensamguk.logic.retainer.RetinueHierarchyLink
import opensamguk.logic.retainer.RetinueHierarchyPerson
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class RetinueHierarchyReader(
    private val owners: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val retainers: RetainerReadRepository,
) {
    fun hierarchy(generalId: Int, userId: Long): RetinueHierarchyDto {
        if (generalId <= 0 || owners.resolveGeneralId(userId) != generalId) throw CampForbidden()
        val actor = ownedCampaignGeneral(generals, generalId, userId)
        if (campaignReadGate(worlds, actor) != null) return RetinueHierarchyDto("UNAVAILABLE", generalId)
        val people = generals.findAll()
        val cards = retainers.findAll()
        if (people.any { it.worldId != actor.worldId } || cards.any { it.worldId != actor.worldId } ||
            people.singleOrNull { it.id == actor.id }?.nationId != actor.nationId ||
            cards.map { it.id }.distinct().size != cards.size || cards.any { it.id <= 0 })
            return RetinueHierarchyDto("UNAVAILABLE", generalId)
        val tree = try {
            RetinueHierarchy.build(people.map { RetinueHierarchyPerson(it.id, it.nationId) },
                cards.mapNotNull { card -> card.generalId?.let { RetinueHierarchyLink(card.masterGeneralId, it) } })
        } catch (_: IllegalArgumentException) { return RetinueHierarchyDto("UNAVAILABLE", generalId) }
        val byId = people.associateBy { it.id }
        val superiors = tree.ancestors(generalId).map { id ->
            RetinueSuperiorDto(id, byId.getValue(id).name, tree.parentByPerson[id])
        }
        val nodes = tree.subtree(generalId).map { id ->
            RetinueHierarchyNodeDto(id, byId.getValue(id).name, tree.parentByPerson[id],
                tree.childrenByOwner[id].orEmpty().size, tree.subtree(id).size - 1)
        }
        return RetinueHierarchyDto("READY", generalId, superiors, nodes)
    }
}
