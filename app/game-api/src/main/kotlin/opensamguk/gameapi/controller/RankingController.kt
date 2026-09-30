package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.BestGeneral
import opensamguk.gameapi.dto.KingdomRank
import opensamguk.gameapi.dto.KingdomRoster
import opensamguk.gameapi.rank.RankReadService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 기존 화면이 쓰는 인물·세력 순위 조회. 화면 대체 후 은퇴하며 공개 읽기 정책을 유지한다.
 */
@RestController
@RequestMapping("/api/rankings")
class RankingController(
    private val rankReadService: RankReadService,
) {

    @GetMapping("/best-generals")
    fun bestGenerals(): ResponseEntity<List<BestGeneral>> =
        ResponseEntity.ok(rankReadService.bestGenerals())

    @GetMapping("/kingdoms")
    fun kingdoms(): ResponseEntity<List<KingdomRank>> =
        ResponseEntity.ok(rankReadService.kingdoms())

    /**
     * 세력일람(a_kingdomList.php, fid 15) ROSTER — `/kingdoms`(leaderboard)와 별개 화면.
     * 국가별 수뇌직책표 + 속령/장수 일람 + 재야 섹션을 반환한다(read-only).
     */
    @GetMapping("/kingdom-roster")
    fun kingdomRoster(): ResponseEntity<KingdomRoster> =
        ResponseEntity.ok(rankReadService.kingdomRoster())
}
