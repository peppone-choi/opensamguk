package opensamguk.gameapi.web

import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 6 — `GET /api/city/{id}` (W4 `MapCityDetail` + the W5 main screen). The full city read shape:
 * the scalar surface + the develop/defense pairs (value + max) + trust/trade/region + the stationed
 * officer count.
 *
 * READ-ONLY: assembled from the existing precheck [CityReadRepository] (the legitimate JPA read — §7)
 * plus a cheap `countByCityId` on [GeneralReadRepository]. 404 when the city id is absent. Public read
 * (no identity needed — the map/city panel renders for anonymous lobby visitors too).
 */
@RestController
@RequestMapping("/api")
class CityDetailController(
    private val cities: CityReadRepository,
    private val generals: GeneralReadRepository,
) {

    /** The full city-detail shape (W4 MapCityDetail + W5). */
    data class CityDetailResponse(
        val id: Int,
        val name: String,
        val level: Int,
        val region: Int,
        val nationId: Int,
        val population: Int,
        val populationMax: Int,
        val agriculture: Int,
        val agricultureMax: Int,
        val commerce: Int,
        val commerceMax: Int,
        val security: Int,
        val securityMax: Int,
        val defense: Int,
        val defenseMax: Int,
        val wall: Int,
        val wallMax: Int,
        val trust: Double,
        val trade: Int?,
        val supplyState: Int,
        val frontState: Int,
        val officers: Long,
    )

    @GetMapping("/city/{id}")
    fun city(@PathVariable id: Int): ResponseEntity<CityDetailResponse> {
        val c = cities.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            CityDetailResponse(
                id = c.id,
                name = c.name,
                level = c.level,
                region = c.region,
                nationId = c.nationId,
                population = c.population,
                populationMax = c.populationMax,
                agriculture = c.agriculture,
                agricultureMax = c.agricultureMax,
                commerce = c.commerce,
                commerceMax = c.commerceMax,
                security = c.security,
                securityMax = c.securityMax,
                defense = c.defense,
                defenseMax = c.defenseMax,
                wall = c.wall,
                wallMax = c.wallMax,
                trust = c.trust,
                trade = c.trade,
                supplyState = c.supplyState,
                frontState = c.frontState,
                officers = generals.countByCityId(c.id),
            ),
        )
    }
}
