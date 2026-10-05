package opensamguk.gameapi.people

import opensamguk.gameapi.dto.DirectoryAffiliation
import opensamguk.gameapi.dto.DirectoryPortrait
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * K4-13 person detail. Ownership of the acting general is verified before the target is read. Public fields
 * (name · portrait · affiliation · five abilities · aptitudes) follow the people directory; private fields
 * (role · lord · location · bonds · injury · retinue card) open only for SELF and the actor's direct RETINUE.
 * Placement and offices have no agreed producer yet, so they are always null with CONTRACT_PENDING.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class PersonDetailReader(
    private val owners: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val nations: NationReadRepository,
    private val retainers: RetainerReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    private val camp: CampReader,
) {
    fun person(targetGeneralId: Int, generalId: Int, userId: Long): PersonDetailDto? {
        // A borrowed body must fail before the target person is read.
        if (owners.resolveGeneralId(userId) != generalId) throw CampForbidden()
        val actor = ownedCampaignGeneral(generals, generalId, userId)
        if (targetGeneralId <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid person")
        campaignReadGate(worlds, actor)?.let { return PersonDetailDto(it, targetGeneralId) }
        val target = generals.findById(targetGeneralId).orElse(null) ?: return null
        val cards = retainers.findAll()
        val countries = nations.findAll()
        if (target.worldId != actor.worldId || cards.any { it.worldId != actor.worldId } || countries.any { it.worldId != actor.worldId })
            throw ResponseStatusException(HttpStatus.CONFLICT, "Person detail world mismatch")
        val targetCards = cards.filter { it.generalId == target.id }
        val relation = PersonDetailProjection.relation(actor, target, targetCards)
        val reasons = linkedMapOf<String, String>()

        val nation = countries.singleOrNull { it.id == target.nationId && it.id > 0 }
        if (target.nationId > 0 && nation == null) reasons["/affiliation"] = "INVALID_SOURCE"
        val stats = PersonDetailProjection.stats(target)
        val aptitudes = PersonDetailProjection.aptitudes(stats)
        if (stats == null) reasons["/stats"] = "INVALID_SOURCE"
        if (aptitudes == null) reasons["/aptitudes"] = "INVALID_SOURCE"

        val personal = Personal()
        if (relation == PersonDetailProjection.SELF || relation == PersonDetailProjection.RETINUE) {
            fillPersonal(personal, actor, target, targetCards, relation, userId, reasons)
        } else {
            for (field in PRIVATE_FIELDS) reasons[field] = "NOT_AUTHORIZED"
        }
        reasons["/placement"] = "CONTRACT_PENDING"
        reasons["/offices"] = "CONTRACT_PENDING"

        return PersonDetailDto(
            status = if (reasons.values.any { it == "INVALID_SOURCE" }) "PARTIAL" else "READY",
            generalId = target.id, relation = relation, name = target.name,
            portrait = DirectoryPortrait(target.picture, target.imageServer),
            affiliation = nation?.let { DirectoryAffiliation(it.id, it.name, it.color) },
            stats = stats, aptitudes = aptitudes, role = personal.role, lordGeneralId = personal.lordGeneralId,
            location = personal.location, bonds = personal.bonds, injured = personal.injured, retinue = personal.card,
            unavailableReasons = reasons,
        )
    }

    private class Personal {
        var role: String? = null
        var lordGeneralId: Int? = null
        var location: PersonLocationDto? = null
        var bonds: List<opensamguk.gameapi.dto.BondDto>? = null
        var injured: Boolean? = null
        var card: PersonRetinueCardDto? = null
    }

    private fun fillPersonal(into: Personal, actor: GeneralReadEntity, target: GeneralReadEntity, targetCards: List<GeneralRetainerReadEntity>,
                            relation: String, userId: Long, reasons: MutableMap<String, String>) {
        val master = targetCards.singleOrNull()?.masterGeneralId
        into.role = PersonDetailProjection.role(target, master)
        if (into.role == null) reasons["/role"] = "INVALID_SOURCE"
        into.lordGeneralId = master
        into.injured = PersonDetailProjection.injured(target)
        if (into.injured == null) reasons["/injured"] = "INVALID_SOURCE"
        into.location = location(target, reasons)
        if (relation == PersonDetailProjection.SELF) {
            // Bonds in the retinue shape are produced for retainer cards only; there is no own-card source for SELF.
            reasons["/bonds"] = "NO_SOURCE"
            reasons["/retinue"] = "NO_SOURCE"
            return
        }
        val retinue = camp.retinue(actor.id, userId)
        val card = retinue.people.filter { it.generalId == target.id }.singleOrNull()
        if (retinue.status != "READY" || card == null) {
            reasons["/bonds"] = "INVALID_SOURCE"
            reasons["/retinue"] = "INVALID_SOURCE"
            return
        }
        into.bonds = card.bonds
        into.card = PersonRetinueCardDto(card.retainerId, card.loyalty, card.cost, card.roleLabel, card.taskLabel, card.departureOrder)
        if (card.cost == null) reasons["/retinue/cost"] = "INVALID_SOURCE"
    }

    /** Resolves the stored city in the selected world; a missing or ambiguous city is reported, never named. */
    private fun location(target: GeneralReadEntity, reasons: MutableMap<String, String>): PersonLocationDto? {
        if (target.cityId <= 0) { reasons["/location"] = "NO_SOURCE"; return null }
        val selected = artifacts.resolve()
        if (selected == null || selected.world.id != target.worldId) { reasons["/location"] = "NO_SNAPSHOT"; return null }
        val city = selected.cities.filter { it.id == target.cityId }.singleOrNull()
        if (city == null || city.worldId != target.worldId) { reasons["/location"] = "INVALID_SOURCE"; return null }
        val place = selected.artifacts?.let { bundle ->
            try { geography.places(bundle)[city.id] }
            catch (_: IllegalArgumentException) { null }
            catch (_: IllegalStateException) { null }
            catch (_: java.io.IOException) { null }
        }
        val name = (place?.displayName ?: city.name).trim()
        if (name.isEmpty()) { reasons["/location"] = "INVALID_SOURCE"; return null }
        return PersonLocationDto(city.id, name)
    }

    private companion object {
        val PRIVATE_FIELDS = listOf("/role", "/lordGeneralId", "/location", "/bonds", "/injured", "/retinue")
    }
}
