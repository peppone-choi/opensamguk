package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.TroopMember
import opensamguk.gameapi.dto.TroopRow
import opensamguk.gameapi.dto.TroopsResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.TroopReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/troops` (부대 편성, spec page 6). READ-only.
 *
 * The `troop` table EXISTS but carries ZERO rows in the fresh scenario_1010 seed → empty list
 * (`{result:true, troops:[]}`), never 500, no fabrication. Each troop is the leader + its member
 * generals (generals whose `troop_id` == the leader id), with city name and crew per member and the
 * `(N명)` member count for the list header.
 *
 * Public read, scoped to the caller's nation when a verified principal resolves; otherwise every troop
 * (still empty in the seed). game-api ONLY (§7) — troop ops are intake, deferred past F4.
 */
@RestController
@RequestMapping("/api/troops")
class TroopController(
    private val troops: TroopReadRepository,
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val resolver: GeneralResolver,
) {

    @GetMapping
    fun list(@AuthenticationPrincipal userId: Long?): ResponseEntity<TroopsResponse> {
        val nationId = userId?.let { resolver.resolve(it)?.nationId } ?: 0
        val troopRows = if (nationId != 0) {
            troops.findByNationOrderByTroopLeaderAsc(nationId)
        } else {
            troops.findAll().sortedBy { it.troopLeader }
        }
        if (troopRows.isEmpty()) {
            return ResponseEntity.ok(TroopsResponse(result = true, troops = emptyList()))
        }

        val cityName = cities.findAll().associate { it.id to it.name }
        val rows = troopRows.map { t ->
            val members = generals.findByTroopIdOrderByOfficerLevelDescIdAsc(t.troopLeader).map { g ->
                TroopMember(
                    generalId = g.id,
                    name = g.name,
                    officerLevel = g.officerLevel,
                    crew = g.crew,
                    cityName = if (g.cityId == 0) "" else (cityName[g.cityId] ?: ""),
                )
            }
            val leaderName = members.firstOrNull { it.generalId == t.troopLeader }?.name
                ?: generals.findById(t.troopLeader).map { it.name }.orElse("")
            TroopRow(
                troopLeader = t.troopLeader,
                name = t.name,
                nation = t.nation,
                leaderName = leaderName,
                members = members,
                memberCount = members.size,
            )
        }
        return ResponseEntity.ok(TroopsResponse(result = true, troops = rows))
    }
}
