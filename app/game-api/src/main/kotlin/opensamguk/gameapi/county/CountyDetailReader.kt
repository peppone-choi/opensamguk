package opensamguk.gameapi.county

import opensamguk.common.constants.CityConst
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.read.*
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
        val indicators = if (full) CountyDetailProjection.indicators(city) else CountyIndicatorsDto()
        val garrison = if (full) CountyDetailProjection.garrison(city) else null
        val income = if (own && full) CountyDetailProjection.income(city) else null
        val countySpecialties = specialties.county(cityId, generalId, userId)
        val stockSources = countySpecialties?.takeIf { it.status == "READY" && it.cityId == cityId }?.specialties?.map {
            if (own && full) it else it.copy(monthly = null)
        }
        val fields = linkedMapOf("population" to indicators.population, "agriculture" to indicators.agriculture,
            "commerce" to indicators.commerce, "security" to indicators.security, "trust" to indicators.trust,
            "defence" to indicators.defence, "wall" to indicators.wall)
        val grade = (CityConst.levelMap[city.level] as? String)?.let { CountyGradeDto(city.level, it) }
        val reasons = linkedMapOf<String, String>()
        for ((field, value) in fields) {
            if (value == null) reasons["/indicators/$field"] = if (full) "INVALID_SOURCE" else "NOT_AUTHORIZED"
            else reasons["/indicators/$field/trend"] = "NO_SOURCE"
        }
        if (grade == null) reasons["/grade"] = "INVALID_SOURCE"
        if (stockSources == null) reasons["/specialties"] = "NO_SOURCE"
        if (garrison == null) reasons["/garrison"] = when {
            !full -> "NOT_AUTHORIZED"
            opensamguk.logic.input.CityMilitaryState.META_KEY !in city.meta -> "NO_SOURCE"
            else -> "INVALID_SOURCE"
        }
        if (income == null) reasons["/income"] = if (own && full) "INVALID_SOURCE" else "NOT_AUTHORIZED"
        reasons["/peopleHere"] = "NO_SOURCE"
        reasons["/front"] = "NO_SOURCE"
        reasons["/seasonalEvent"] = "NO_SOURCE"
        val partial = grade == null || stockSources == null || (full && (fields.values.any { it == null } || garrison == null || (own && income == null)))
        val place = places[cityId]
        return CountyDetailDto(status = if (partial) "PARTIAL" else "READY", cityId = cityId,
            name = place?.displayName ?: city.name, nameCh = place?.countyHanja, grade = grade,
            commandery = CountyDirectoryCommandery(group.id, group.name),
            owner = nation?.let { DirectoryAffiliation(it.id, it.name, it.color) }, visibility = seen.tier,
            intelAgeTurns = seen.ageTurns.takeIf { seen.tier == "INTEL" }, indicators = indicators,
            specialties = stockSources, garrison = garrison, income = income,
            stamp = StampDto(world.currentYear, world.currentMonth, world.currentPhase), unavailableReasons = reasons)
    }

    private fun unavailable(cityId: Int) = CountyDetailDto("UNAVAILABLE", cityId)
}
