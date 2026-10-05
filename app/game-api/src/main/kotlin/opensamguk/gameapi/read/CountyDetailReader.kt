package opensamguk.gameapi.read

import opensamguk.common.constants.CityConst
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.world.WorldFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class CountyDetailReader(
    private val owners: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    private val vision: VisionReader,
    private val specialties: CampReader,
) {
    fun county(cityId: Int, generalId: Int, userId: Long): CountyDetailDto? {
        // A borrowed body must fail before reading the target county or its spatial artifacts.
        if (owners.resolveGeneralId(userId) != generalId) throw CampForbidden()
        val actor = ownedCampaignGeneral(generals, generalId, userId)
        if (cityId <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid county")
        val selected = artifacts.resolve() ?: return unavailable(cityId)
        if (actor.worldId != selected.world.id || selected.cities.any { it.worldId != selected.world.id })
            throw ResponseStatusException(HttpStatus.CONFLICT, "County detail world mismatch")
        try { WorldFormat.require(selected.world.config, selected.world.meta) }
        catch (_: IllegalArgumentException) { return CountyDetailDto("UNSUPPORTED_WORLD_FORMAT", cityId) }
        val world = selected.world
        if (world.currentYear < 0 || world.currentMonth !in 1..12 || world.currentPhase !in 1..3)
            return unavailable(cityId)
        val bundle = selected.artifacts ?: return unavailable(cityId)
        val targets = selected.cities.filter { it.id == cityId }
        if (targets.size > 1) return unavailable(cityId)
        val city = targets.singleOrNull() ?: return null
        if (city.id !in bundle.projection.administrativeCountyIds) return null
        val index = try { bundle.commanderyIndex }
        catch (_: IllegalArgumentException) { return unavailable(cityId) }
        catch (_: IllegalStateException) { return unavailable(cityId) }
        catch (_: java.io.IOException) { return unavailable(cityId) }
        val province = bundle.projection.bindingsByCityId[cityId]?.landProvinceId ?: return unavailable(cityId)
        val commanderyNo = index.commanderyOf(province) ?: return unavailable(cityId)
        val group = index.commanderies[commanderyNo]
        val visible = vision.visibility(generalId, userId)
        val entries = visible.commanderies ?: return unavailable(cityId)
        if (visible.status != "READY" || visible.stamp != StampDto(world.currentYear, world.currentMonth, world.currentPhase) ||
            entries.size != index.commanderies.size || entries.map { it.id }.toSet() != index.commanderies.map { it.id }.toSet() ||
            entries.any { it.tier !in setOf("FULL", "INTEL", "FOG") || index.byId(it.id)?.no != it.no })
            return unavailable(cityId)
        val seen = entries.single { it.id == group.id }
        if (seen.tier == "INTEL" && (seen.ageTurns == null || seen.ageTurns < 0)) return unavailable(cityId)
        val places = try { geography.places(bundle) }
        catch (_: IllegalArgumentException) { return unavailable(cityId) }
        catch (_: IllegalStateException) { return unavailable(cityId) }
        catch (_: java.io.IOException) { return unavailable(cityId) }
        val countries = nations.findAll()
        if (countries.any { it.worldId != world.id })
            throw ResponseStatusException(HttpStatus.CONFLICT, "County detail nation world mismatch")
        val nation = countries.singleOrNull { it.id == city.nationId && it.id > 0 }
        if (city.nationId > 0 && nation == null) return unavailable(cityId)
        val own = actor.nationId > 0 && city.nationId == actor.nationId
        val full = seen.tier == "FULL"
        // INTEL is not a current county-metric snapshot; decode military/income only after masking.
        val population = if (full) city.population.takeIf { it >= 0 } else null
        val defense = if (full) city.defense.takeIf { it >= 0 } else null
        val garrison = if (full) CountyDetailProjection.garrison(city) else null
        val income = if (own && full) CountyDetailProjection.income(city) else null
        val countySpecialties = specialties.county(cityId, generalId, userId)
        val stockSources = countySpecialties?.takeIf { it.status == "READY" && it.cityId == cityId }?.specialties?.map {
            if (own && full) it else it.copy(monthly = null)
        }
        val partial = stockSources == null || (full && (population == null || defense == null || garrison == null || (own && income == null)))
        val place = places[cityId]
        return CountyDetailDto(if (partial) "PARTIAL" else "READY", cityId,
            place?.displayName ?: city.name, place?.countyHanja, city.level, CityConst.levelMap[city.level] as? String,
            CountyDirectoryCommandery(group.id, group.name), nation?.let { DirectoryAffiliation(it.id, it.name, it.color) },
            seen.tier, seen.ageTurns.takeIf { seen.tier == "INTEL" }, population, defense, stockSources, garrison, income)
    }

    private fun unavailable(cityId: Int) = CountyDetailDto("UNAVAILABLE", cityId)
}
