package opensamguk.gameapi.court.imperial

import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialWorldCodec
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.Collections

/** D123 public court fields, without presence/spatial or private imperial data dependencies. */
@Component
class ImperialCourtReader(
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(observerWorldId: Int): ImperialCourtDto = try {
        val world = requireNotNull(worlds.findProcessWorld())
        require(world.id > 0 && world.id == observerWorldId)
        val imperial = ImperialWorldCodec.read(world.meta)
        if (imperial == null) {
            ImperialCourtDto(ImperialCourtStatus.NOT_SEEDED, emptyList())
        } else {
            val courtIds = imperial.houses.filter { it.status == ImperialLineStatus.ACTIVE }
                .mapNotNull { it.courtCityId }.distinct()
            val courtNames = if (courtIds.isEmpty()) emptyMap() else {
                val selected = requireNotNull(artifacts.resolve())
                require(selected.world.id == world.id)
                val projection = requireNotNull(selected.artifacts).projection
                courtIds.associateWith { id ->
                    require(id in projection.bindingsByCityId)
                    val city = requireNotNull(cities.findById(id).orElse(null))
                    require(city.id == id && city.worldId == world.id)
                    city.name.takeIf { it.isNotBlank() }
                }
            }
            val lines = imperial.houses.sortedBy { it.code }.map { house ->
                if (house.status != ImperialLineStatus.ACTIVE) {
                    val state = ImperialCourtFieldState.NOT_APPLICABLE
                    ImperialCourtLineDto(house.code, house.name, house.status.name,
                        null, null, null, null, null, null, null, null,
                        ImperialCourtFieldStates(state, state, state, state))
                } else activeLine(house, world.id, courtNames)
            }
            ImperialCourtDto(ImperialCourtStatus.READY, Collections.unmodifiableList(lines))
        }
    } catch (cause: IllegalArgumentException) {
        throw ImperialCourtUnavailable(cause)
    } catch (cause: IllegalStateException) {
        throw ImperialCourtUnavailable(cause)
    } catch (cause: ResponseStatusException) {
        if (cause.statusCode == HttpStatus.CONFLICT) throw ImperialCourtUnavailable(cause)
        throw cause
    }

    private fun activeLine(house: ImperialHouse, worldId: Int, courtNames: Map<Int, String?>): ImperialCourtLineDto {
        val holderName = generalName(requireNotNull(house.holderGeneralId), worldId)
        val regentName = house.regentGeneralId?.let { generalName(it, worldId) }
        val nationName = house.courtNationId?.let { id ->
            val nation = requireNotNull(nations.findById(id).orElse(null))
            require(nation.id == id && nation.worldId == worldId)
            nation.name.takeIf { it.isNotBlank() }
        }
        val courtName = house.courtCityId?.let { courtNames.getValue(it) }
        return ImperialCourtLineDto(house.code, house.name, house.status.name,
            house.holderGeneralId, holderName, house.courtCityId, courtName,
            house.regentGeneralId, regentName, house.courtNationId, nationName,
            ImperialCourtFieldStates(fieldState(house.holderGeneralId, holderName),
                fieldState(house.courtCityId, courtName), fieldState(house.regentGeneralId, regentName),
                fieldState(house.courtNationId, nationName)))
    }

    private fun generalName(id: Int, worldId: Int): String? {
        val general = requireNotNull(generals.findById(id).orElse(null))
        require(general.id == id && general.worldId == worldId)
        return general.name.takeIf { it.isNotBlank() }
    }

    private fun fieldState(id: Int?, name: String?): ImperialCourtFieldState =
        if (id != null && name == null) ImperialCourtFieldState.UNAVAILABLE else ImperialCourtFieldState.READY
}
