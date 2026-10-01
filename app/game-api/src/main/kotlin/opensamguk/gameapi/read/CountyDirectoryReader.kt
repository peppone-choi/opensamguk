package opensamguk.gameapi.read

import opensamguk.gameapi.dto.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.economy.CountyIncome
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.world.WorldFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/** Income slice of the county directory: only the viewer's nation, with canonical live vision. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class CountyDirectoryReader(
    private val owners: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val geography: CityGeography,
    private val vision: VisionReader,
) {
    fun counties(generalId: Int, userId: Long, scope: String, commanderyId: String?): CountyDirectoryResponse {
        if (scope !in setOf("NATION", "COMMANDERY") ||
            (scope == "COMMANDERY" && (commanderyId.isNullOrBlank() || commanderyId.length > 200)) ||
            (scope == "NATION" && commanderyId != null))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid county scope")
        if (owners.resolveGeneralId(userId) != generalId) throw CampForbidden()
        val actor = ownedCampaignGeneral(generals, generalId, userId)
        val selected = artifacts.resolve() ?: return CountyDirectoryResponse("UNAVAILABLE", scope)
        if (actor.worldId != selected.world.id || selected.cities.any { it.worldId != selected.world.id })
            throw ResponseStatusException(HttpStatus.CONFLICT, "County directory world mismatch")
        if (runCatching { WorldFormat.require(selected.world.config, selected.world.meta) }.isFailure)
            return CountyDirectoryResponse("UNSUPPORTED_WORLD_FORMAT", scope)
        if (selected.world.currentYear < 0 || selected.world.currentMonth !in 1..12 || selected.world.currentPhase !in 1..3)
            return CountyDirectoryResponse("UNAVAILABLE", scope)
        if (actor.nationId <= 0) return CountyDirectoryResponse("NO_NATION", scope)
        val bundle = selected.artifacts ?: return CountyDirectoryResponse("UNAVAILABLE", scope)
        val index = try { bundle.commanderyIndex }
            catch (_: IllegalArgumentException) { return CountyDirectoryResponse("UNAVAILABLE", scope) }
            catch (_: IllegalStateException) { return CountyDirectoryResponse("UNAVAILABLE", scope) }
            catch (_: java.io.IOException) { return CountyDirectoryResponse("UNAVAILABLE", scope) }
        val commandery = if (scope == "COMMANDERY") index.byId(requireNotNull(commanderyId))
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown commandery") else null
        val selectedCommandery = commandery?.let { CountyDirectoryCommandery(it.id, it.name) }
        val visible = vision.visibility(generalId, userId)
        val stamp = StampDto(selected.world.currentYear, selected.world.currentMonth, selected.world.currentPhase)
        if (visible.status != "READY" || visible.stamp != stamp)
            return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery)
        val tiers = visible.commanderies?.associate { it.id to it.tier }
            ?: return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery)
        if (tiers.keys != index.commanderies.map { it.id }.toSet() || tiers.values.any { it !in setOf("FULL", "INTEL", "FOG") })
            return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery)
        val places = try { geography.places(bundle) }
            catch (_: IllegalArgumentException) { return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery) }
            catch (_: IllegalStateException) { return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery) }
            catch (_: java.io.IOException) { return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery) }
        val owned = selected.cities.filter { it.id in bundle.projection.administrativeCountyIds && it.nationId == actor.nationId }
        val rows = ArrayList<CountyDirectoryRow>()
        for (city in owned.sortedBy { it.id }) {
            val province = bundle.projection.bindingsByCityId[city.id]?.landProvinceId
                ?: return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery)
            val no = index.commanderyOf(province) ?: return CountyDirectoryResponse("UNAVAILABLE", scope, selectedCommandery)
            val group = index.commanderies[no]
            if (commandery != null && group.id != commandery.id) continue
            val tier = requireNotNull(tiers[group.id])
            rows += CountyDirectoryRow(city.id, places[city.id]?.displayName ?: city.name, group.id, tier,
                if (tier == "FULL") forecast(city) else null)
        }
        return CountyDirectoryResponse(if (rows.any { it.visibility == "FULL" && it.income == null }) "PARTIAL" else "READY",
            scope, selectedCommandery, stamp = stamp, counties = rows)
    }

    private fun forecast(city: CityReadEntity): CountyIncomeDto? = try {
        val warehouse = CountyWarehouse.read(city.meta, city.id)
        if (warehouse == null) CountyIncomeDto(0, 0) else {
            val produced = CountyIncome.monthly(CountyIncome.CountyState(city.nationId, city.population,
                city.commerce, city.commerceMax, city.agriculture, city.agricultureMax, city.supplyState != 0))
            CountyIncomeDto(produced.money, produced.grain)
        }
    } catch (_: IllegalArgumentException) { null }
      catch (_: ArithmeticException) { null }
}
