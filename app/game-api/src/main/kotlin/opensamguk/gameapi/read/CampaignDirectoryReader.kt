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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** A directory cursor binds its position to the process world, viewer and exact filter. It grants no access. */
internal object PeopleCursor {
    fun context(worldId: Int, viewerId: Int, scope: String, query: String): String =
        MessageDigest.getInstance("SHA-256").digest("$worldId|$viewerId|$scope|$query".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun encode(context: String, lastId: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("1|$context|$lastId".toByteArray(StandardCharsets.US_ASCII))

    fun decode(value: String?, context: String): Int {
        if (value == null) return 0
        return try {
            require(value.length in 1..160 && value.matches(Regex("[A-Za-z0-9_-]+")))
            val parts = String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split('|')
            require(parts.size == 3 && parts[0] == "1" && parts[1] == context)
            parts[2].toInt().also { require(it > 0) }
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid people cursor")
        }
    }
}

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

    fun people(userId: Long, scope: String, query: String, sort: String, cursor: String?, limit: Int): PeoplePage {
        validatePage(scope, query, sort, limit)
        val id = owners.resolveGeneralId(userId) ?: return PeoplePage("NO_GENERAL")
        val actor = ownedCampaignGeneral(generals, id, userId)
        val frame = frame() ?: return PeoplePage("UNAVAILABLE")
        checkWorld(frame.worldId, listOf(actor.worldId))
        return page(frame, actor, scope, query.trim(), cursor, limit, admin = false)
    }

    /** Called only after the controller verifies the ADMIN role. */
    fun adminPeople(query: String, sort: String, cursor: String?, limit: Int): PeoplePage {
        validatePage("ALL", query, sort, limit)
        val frame = frame() ?: return PeoplePage("UNAVAILABLE")
        return page(frame, null, "ALL", query.trim(), cursor, limit, admin = true)
    }

    private fun validatePage(scope: String, query: String, sort: String, limit: Int) {
        if (scope !in setOf("ALL", "NATION", "RETINUE") || sort != "ID" || query.length > 100 || limit !in 1..100)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid people filter")
    }

    private fun page(frame: Frame, actor: GeneralReadEntity?, scope: String, query: String,
                     cursor: String?, limit: Int, admin: Boolean): PeoplePage {
        val ownIds = actor?.let { self -> frame.cards.filter { it.masterGeneralId == self.id }
            .mapNotNull { it.generalId }.toSet() + self.id }.orEmpty()
        val context = PeopleCursor.context(frame.worldId, actor?.id ?: 0, scope, query)
        val after = PeopleCursor.decode(cursor, context)
        val hits = frame.people.asSequence().filter { it.id > after }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .filter { when (scope) {
                "NATION" -> actor != null && actor.nationId > 0 && it.nationId == actor.nationId
                "RETINUE" -> it.id in ownIds
                else -> true
            } }.sortedBy { it.id }.take(limit + 1).toList()
        val rows = hits.take(limit).map { person(frame, it, admin || it.id in ownIds) }
        return PeoplePage("READY", rows, if (hits.size > limit) PeopleCursor.encode(context, rows.last().generalId) else null)
    }

    private fun person(frame: Frame, g: GeneralReadEntity, full: Boolean): DirectoryPerson {
        val nation = frame.nations.singleOrNull { it.id == g.nationId && it.id > 0 }
        val policy = if (full) runCatching { PersonPolicyState.read(g.meta) }.getOrNull() else null
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
        val actor = ownedCampaignGeneral(generals, generalId, userId)
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
