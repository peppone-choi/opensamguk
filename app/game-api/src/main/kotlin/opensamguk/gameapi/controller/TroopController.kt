package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.TroopMember
import opensamguk.gameapi.dto.TroopRow
import opensamguk.gameapi.dto.TroopsResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.TurnTimeFormatter
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam

/**
 * F4 — `GET /api/troops` (부대 편성, spec page 6). READ-only.
 *
 * The `troop` table EXISTS but carries ZERO rows in the fresh scenario_1010 seed → empty list
 * (`{result:true, troops:[]}`), never 500, no fabrication. Each troop is the leader + its member
 * generals (generals whose `troop_id` == the leader id), with city name and crew per member and the
 * `(N명)` member count for the list header.
 *
 * Internal nation read. Verified principal resolves the owned general's positive nation; anonymous,
 * nationless and foreign nation requests cannot fall back to an all-troop query.
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
    fun list(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(name = "nationId", required = false) requestedNationId: Int?,
    ): ResponseEntity<TroopsResponse> {
        if (userId == null || userId <= 0) return ResponseEntity.status(401).build()
        val resolved = resolver.resolve(userId) ?: return ResponseEntity.status(403).build()
        val nationId = resolved.nationId
        if (nationId <= 0 || (requestedNationId != null && requestedNationId != nationId)) {
            return ResponseEntity.status(403).build()
        }
        val myGeneralId = resolved.general.id
        val permission = resolved.permission
        val troopRows = troops.findByNationOrderByTroopLeaderAsc(nationId)
        if (troopRows.isEmpty()) {
            return ResponseEntity.ok(
                TroopsResponse(result = true, troops = emptyList(), myGeneralId = myGeneralId, permission = permission),
            )
        }

        val cityName = cities.findAll().associate { it.id to it.name }
        val rows = troopRows.map { t ->
            val members = generals.findByTroopIdOrderByOfficerLevelDescIdAsc(t.troopLeader)
                .filter { it.nationId == nationId }.map { g ->
                TroopMember(
                    generalId = g.id,
                    name = g.name,
                    officerLevel = g.officerLevel,
                    crew = g.crew,
                    cityName = if (g.cityId == 0) "" else (cityName[g.cityId] ?: ""),
                    npc = g.npcState,
                )
            }
            // 부대장 행(roster 멤버) → 없으면 직접 조회로 폴백. 헤더 도시/턴/색상은 부대장 장수에서 취한다.
            val leader = members.firstOrNull { it.generalId == t.troopLeader }
            val leaderEntity = generals.findById(t.troopLeader).orElse(null)?.takeIf { it.nationId == nationId }
            val leaderName = leader?.name ?: leaderEntity?.name ?: ""
            val leaderCityName = leader?.cityName
                ?: leaderEntity?.let { if (it.cityId == 0) "" else (cityName[it.cityId] ?: "") }
                ?: ""
            TroopRow(
                troopLeader = t.troopLeader,
                name = t.name,
                nation = t.nation,
                leaderName = leaderName,
                leaderCityName = leaderCityName,
                leaderNpc = leaderEntity?.npcState ?: 0,
                // 레거시는 부대장 turnTime을 그대로 표시(turnTime.slice(14,19)). null이면 빈 문자열.
                turnTime = TurnTimeFormatter.full(leaderEntity?.turnTime) ?: "",
                // 예약명령 원천이 read 모델에 없어 빈 목록(날조 금지 — 규율 5).
                reservedCommandBrief = emptyList(),
                members = members,
                memberCount = members.size,
            )
        }
        return ResponseEntity.ok(
            TroopsResponse(result = true, troops = rows, myGeneralId = myGeneralId, permission = permission),
        )
    }
}
