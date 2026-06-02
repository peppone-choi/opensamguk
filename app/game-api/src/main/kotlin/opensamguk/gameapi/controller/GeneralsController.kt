package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.PublicGeneral
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/generals` (spec page 14 + 9-P0).
 *
 * PUBLIC, no auth: `GameApiSecurityConfig` ends in `.anyRequest().permitAll()`, so this path needs no
 * principal (the all-general 전체 장수 page must render for an anonymous visitor). Returns ONLY the
 * permission=0 public field surface (id/name/nation/nationColor/officerLevel/L/S/I/crew/cityName) —
 * NO refresh_score, NO exp/gold/rice breakdown (so it can stay unauthenticated, OQ-5). The authed
 * permission-tiered P1/P2 view stays behind `/api/my-generals`.
 *
 * Pure projection of live `general` rows joined to `nation` (name/color) and `city` (name). nationId 0
 * → 재야 / #000000 (mirrors RankReadService neutral join). Read-only; never a game-state write.
 */
@RestController
@RequestMapping("/api/generals")
class GeneralsController(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
) {

    @GetMapping
    fun all(): ResponseEntity<List<PublicGeneral>> {
        val nationName = nations.findAll().associate { it.id to (it.name to it.color) }
        val cityName = cities.findAll().associate { it.id to it.name }
        val rows = generals.findAll()
            .sortedBy { it.id }
            .map { g ->
                val (nm, color) = nationName[g.nationId]
                    ?: (F4StateText.NEUTRAL_NATION_NAME to F4StateText.NEUTRAL_NATION_COLOR)
                PublicGeneral(
                    id = g.id,
                    name = g.name,
                    nation = if (g.nationId == 0) F4StateText.NEUTRAL_NATION_NAME else nm,
                    nationColor = if (g.nationId == 0) F4StateText.NEUTRAL_NATION_COLOR else color,
                    officerLevel = g.officerLevel,
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    crew = g.crew,
                    cityName = if (g.cityId == 0) "" else (cityName[g.cityId] ?: ""),
                )
            }
        return ResponseEntity.ok(rows)
    }
}
