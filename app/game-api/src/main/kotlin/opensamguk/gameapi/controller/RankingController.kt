package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.BestGeneral
import opensamguk.gameapi.dto.EmperorRecord
import opensamguk.gameapi.dto.GeneralRank
import opensamguk.gameapi.dto.HallRecord
import opensamguk.gameapi.dto.KingdomRank
import opensamguk.gameapi.dto.NpcGeneral
import opensamguk.gameapi.dto.TrafficSummary
import opensamguk.gameapi.rank.RankReadService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * F3 — `GET /api/rankings/...` read API (spec `2026-06-02-F3-rankings-spec.md`).
 *
 * Eight ranking endpoints the `web/game` `/game/rankings` pages consume through the same-origin
 * `/api/game` proxy. Public read: `GameApiSecurityConfig` ends in `.anyRequest().permitAll()`, so these
 * paths need no auth — an anonymous visitor must see every board (esp. npcs/traffic). Do NOT add a
 * `.authenticated()` matcher for the rankings paths.
 *
 * Computed boards (best-generals/generals/kingdoms/npcs) are pure projections of live `general`/`nation`/
 * `city` rows ([RankReadService]). emperor/traffic/hall-of-fame return the documented empty/zero defaults
 * (no source table — OQ-1/2/5, NOT fabricated). `emperor/{id}` is 404 until a dynasty-history table exists
 * (the emperor page returns `[]` → no UI link → the detail page only hits this via `.catch()`).
 */
@RestController
@RequestMapping("/api/rankings")
class RankingController(
    private val rankReadService: RankReadService,
) {

    @GetMapping("/best-generals")
    fun bestGenerals(): ResponseEntity<List<BestGeneral>> =
        ResponseEntity.ok(rankReadService.bestGenerals())

    @GetMapping("/generals")
    fun generals(): ResponseEntity<List<GeneralRank>> =
        ResponseEntity.ok(rankReadService.generals())

    @GetMapping("/kingdoms")
    fun kingdoms(): ResponseEntity<List<KingdomRank>> =
        ResponseEntity.ok(rankReadService.kingdoms())

    @GetMapping("/npcs")
    fun npcs(): ResponseEntity<List<NpcGeneral>> =
        ResponseEntity.ok(rankReadService.npcs())

    @GetMapping("/hall-of-fame")
    fun hallOfFame(): ResponseEntity<List<HallRecord>> =
        ResponseEntity.ok(rankReadService.hallOfFame())

    @GetMapping("/traffic")
    fun traffic(): ResponseEntity<TrafficSummary> =
        ResponseEntity.ok(rankReadService.traffic())

    @GetMapping("/emperor")
    fun emperor(): ResponseEntity<List<EmperorRecord>> =
        ResponseEntity.ok(rankReadService.emperor())

    /**
     * No `emperior`/unification-history table (OQ-1) → always 404. The emperor page returns `[]` so no
     * link reaches this path through the UI; the detail page handles the 404 in its `.catch()` branch.
     */
    @GetMapping("/emperor/{id}")
    fun emperorDetail(@PathVariable id: Int): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "no emperor record $id")
}
