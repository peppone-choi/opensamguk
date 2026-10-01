package opensamguk.gameapi.read

import opensamguk.gameapi.dto.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.content.PersonBondState
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticRules
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Aptitude
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.PersonPolicyState
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class CampaignDirectoryReader(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val retainers: RetainerReadRepository,
    private val owners: GeneralResolver,
) {
    private data class Frame(
        val worldId: Int,
        val people: List<GeneralReadEntity>,
        val nations: List<NationReadEntity>,
        val cards: List<GeneralRetainerReadEntity>,
    )

    private fun frame(): Frame? {
        val world = worlds.findProcessWorld() ?: return null
        val people = generals.findAll()
        val countries = nations.findAll()
        val cards = retainers.findAll()
        checkWorld(world.id, people.map { it.worldId } + countries.map { it.worldId } + cards.map { it.worldId })
        return Frame(world.id, people, countries, cards)
    }

    fun people(userId: Long, scope: String, query: String, sort: String, cursor: String?, limit: Int, direction: String = "ASC"): PeoplePage {
        val ordering = validatePage(scope, query, sort, limit, direction)
        val id = owners.resolveGeneralId(userId) ?: return PeoplePage("NO_GENERAL")
        val actor = ownedHwihaGeneral(generals, id, userId)
        val frame = frame() ?: return PeoplePage("UNAVAILABLE")
        checkWorld(frame.worldId, listOf(actor.worldId))
        return page(frame, actor, scope, PeopleNameSearch.normalize(query.trim()), cursor, limit, admin = false, ordering = ordering)
    }

    /** Called only after the controller verifies the ADMIN role. */
    fun adminPeople(query: String, sort: String, cursor: String?, limit: Int, direction: String = "ASC"): PeoplePage {
        val ordering = validatePage("ALL", query, sort, limit, direction)
        val frame = frame() ?: return PeoplePage("UNAVAILABLE")
        return page(frame, null, "ALL", PeopleNameSearch.normalize(query.trim()), cursor, limit, admin = true, ordering = ordering)
    }

    private fun validatePage(scope: String, query: String, sort: String, limit: Int,
                             direction: String): Pair<PeopleSort, PeopleDirection> {
        if (scope !in setOf("ALL", "NATION", "RETINUE") || query.length > 100 || limit !in 1..100)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid people filter")
        return try {
            PeopleSort.valueOf(sort.uppercase(java.util.Locale.ROOT)) to
                PeopleDirection.valueOf(direction.uppercase(java.util.Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid people ordering")
        }
    }

    private fun page(frame: Frame, actor: GeneralReadEntity?, scope: String, query: String,
                     cursor: String?, limit: Int, admin: Boolean,
                     ordering: Pair<PeopleSort, PeopleDirection>): PeoplePage {
        val ownIds = actor?.let { self -> frame.cards.filter { it.masterGeneralId == self.id }
            .mapNotNull { it.generalId }.toSet() + self.id }.orEmpty()
        val (sort, direction) = ordering
        val context = PeopleCursor.digest(listOf(frame.worldId.toString(), (actor?.id ?: 0).toString(),
            admin.toString(), scope, query, sort.name, direction.name, (actor?.nationId ?: 0).toString(),
            ownIds.sorted().joinToString(",")))
        val after = PeopleCursor.decode(cursor, context)
        val candidates = frame.people.asSequence()
            .filter { PeopleNameSearch.matches(it.name, query) }
            .filter { when (scope) {
                "NATION" -> actor != null && actor.nationId > 0 && it.nationId == actor.nationId
                "RETINUE" -> it.id in ownIds
                else -> true
            } }.map { PeopleDirectoryRow(person(frame, it, admin || it.id in ownIds), it.age.takeIf { age -> age >= 0 }) }
            .toList()
        val revision = PeopleCursor.revision(candidates)
        if (after != null && after.revision != revision) PeopleCursor.changed()
        val anchor = after?.let { position ->
            candidates.singleOrNull { it.person.generalId == position.lastId }
                ?.also { if (sort.key(it).wire() != position.key) PeopleCursor.invalid() }
                ?: PeopleCursor.invalid()
        }
        val comparator = peopleComparator(sort, direction)
        val hits = candidates.sortedWith(comparator).asSequence()
            .filter { anchor == null || comparator.compare(it, anchor) > 0 }.take(limit + 1).toList()
        val selected = hits.take(limit)
        return PeoplePage("READY", selected.map { it.person }, if (hits.size > limit) {
            val last = selected.last()
            PeopleCursor.encode(context, revision, last.person.generalId, sort.key(last))
        } else null)
    }

    private fun person(frame: Frame, g: GeneralReadEntity, full: Boolean): DirectoryPerson {
        val nation = frame.nations.singleOrNull { it.id == g.nationId && it.id > 0 }
        val policy = runCatching { PersonPolicyState.read(g.meta) }.getOrNull()
        val stats = if (policy != null && listOf(g.leadership, g.strength, g.intel, g.politics, g.charm).all { it >= 0 })
            DirectoryStats(g.leadership, g.strength, g.intel, g.politics, g.charm) else null
        val aptitude = stats?.let { Aptitude.compute(Aptitude.Stats(it.leadership, it.strength, it.intel, it.politics, it.charm)) }
        val master = if (full) frame.cards.filter { it.generalId == g.id }.singleOrNull()?.masterGeneralId else null
        val role = if (!full) null else runCatching {
            when { LordStatus.read(g.meta) -> "LORD"; master != null -> "RETAINER"; else -> "FREE" }
        }.getOrNull()
        val bonds = if (full) runCatching { PersonBondState.read(g.meta)?.bonds?.sortedWith(
            compareBy({ it.kind.name }, { it.targetId }))?.map { DirectoryBond(it.kind.name, it.targetId) } }.getOrNull() else null
        return DirectoryPerson(g.id, g.name, DirectoryPortrait(g.picture, g.imageServer),
            nation?.let { DirectoryAffiliation(it.id, it.name, it.color) }, role, master,
            stats, aptitude?.let { DirectoryAptitudes(it.command, it.administration, it.strategy, it.envoy) },
            g.cityId.takeIf { full && it > 0 }, bonds)
    }

    fun nationSummary(generalId: Int, userId: Long): CampaignNationSummary {
        if (owners.resolveGeneralId(userId) != generalId) throw CampForbidden()
        val actor = ownedHwihaGeneral(generals, generalId, userId)
        val frame = frame() ?: return CampaignNationSummary("UNAVAILABLE")
        checkWorld(frame.worldId, listOf(actor.worldId))
        if (actor.nationId <= 0) return CampaignNationSummary("NO_NATION")
        val nation = frame.nations.singleOrNull { it.id == actor.nationId }
            ?: return CampaignNationSummary("UNAVAILABLE")
        val counties = administrativeCounties(frame.worldId) ?: return CampaignNationSummary("UNAVAILABLE")
        val units = retainers.allBugoks()
        checkWorld(frame.worldId, counties.map { it.worldId } + units.map { it.worldId })
        return summary(frame, nation, counties, units)
    }

    /** No player query parameter can select or impersonate an administrator. */
    fun adminNations(): AdminNationDirectory {
        val frame = frame() ?: return AdminNationDirectory("UNAVAILABLE")
        val counties = administrativeCounties(frame.worldId) ?: return AdminNationDirectory("UNAVAILABLE")
        val units = retainers.allBugoks()
        checkWorld(frame.worldId, counties.map { it.worldId } + units.map { it.worldId })
        val rows = frame.nations.filter { it.id > 0 }.sortedBy { it.id }.map { summary(frame, it, counties, units) }
        return AdminNationDirectory(if (rows.any { it.status != "READY" }) "PARTIAL" else "READY", rows)
    }

    private fun administrativeCounties(worldId: Int): List<CityReadEntity>? {
        val selected = artifacts.resolve() ?: return null
        checkWorld(worldId, listOf(selected.world.id) + selected.cities.map { it.worldId })
        val admin = selected.artifacts?.projection?.administrativeCountyIds ?: return null
        return selected.cities.filter { it.id in admin }
    }

    private fun summary(frame: Frame, nation: NationReadEntity, counties: List<CityReadEntity>,
                        units: List<GeneralBugokReadEntity>): CampaignNationSummary {
        val owned = counties.filter { it.nationId == nation.id }
        val masters = frame.people.filter { it.nationId == nation.id }.map { it.id }.toSet()
        val cards = frame.cards.filter { it.masterGeneralId in masters }
        val nationalUnits = units.filter { it.masterGeneralId in masters }
        val stock = runCatching {
            owned.fold(Resources()) { total, city ->
                val row = CountyWarehouse.read(city.meta, city.id)
                if (row == null) total else total.credit(row.stock)
            }
        }.getOrNull()
        val cityTroops = runCatching { owned.sumOf {
            CityMilitaryState.read(it.meta, it.defense.coerceAtLeast(0)).troops.toLong()
        } }.getOrNull()
        val bugokTroops = nationalUnits.takeIf { all -> all.all { it.troops >= 0 } }?.sumOf { it.troops.toLong() }
        // Ruler selection only consumes nation, office and LordStatus; spatial state is not needed.
        val rulerId = runCatching { DomesticRules.rulerOf(nation.id, frame.people.map { g ->
            DomesticPerson(g.id, g.name, g.nationId, (g.userId?.toLongOrNull() ?: 0) > 0, g.npcState,
                g.officerLevel, g.leadership, g.strength, g.intel, g.politics, g.charm,
                node = null, inBattle = false, meta = g.meta)
        })?.id }.getOrNull()
        val lord = frame.people.singleOrNull { it.id == rulerId }
        return CampaignNationSummary(if (stock == null || cityTroops == null || bugokTroops == null) "PARTIAL" else "READY",
            SummaryNation(nation.id, nation.name, nation.color),
            lord?.let { SummaryLord(it.id, it.name, DirectoryPortrait(it.picture, it.imageServer)) },
            nation.capitalCityId, owned.size, cards.size,
            stock?.let { StockDto(it.money, it.grain, it.iron, it.timber, it.horses) },
            owned.sumOf { it.population.toLong() }, SummaryTroops(cityTroops, bugokTroops))
    }

    private fun checkWorld(worldId: Int, actual: List<Int>) {
        if (actual.any { it != worldId }) throw ResponseStatusException(HttpStatus.CONFLICT, "Directory world mismatch")
    }
}
