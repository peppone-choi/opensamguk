package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.DiplomacyMatrixResponse
import opensamguk.gameapi.dto.DiplomacyResponse
import opensamguk.gameapi.read.DiplomacyReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/diplomacy")
class DiplomacyController(
    private val diplomacyReadRepository: DiplomacyReadRepository,
) {

    /**
     * 외교 관계 행렬 — 해당 국가가 source인 모든 관계.
     * PHP `GetDiplomacy.php` neutralDiplomacyMap parity:
     * stateCode 3~7 → 2 (neutral)로 마스킹.
     */
    @GetMapping("/{nationId}")
    fun matrix(@PathVariable nationId: Int): ResponseEntity<DiplomacyMatrixResponse> {
        val rows = diplomacyReadRepository.findBySrcNationId(nationId)
            .map {
                val maskedState = if (it.stateCode in 3..7) 2 else it.stateCode
                DiplomacyResponse(
                    srcNationId = it.srcNationId,
                    destNationId = it.destNationId,
                    stateCode = maskedState,
                    term = it.term,
                )
            }
        return ResponseEntity.ok(DiplomacyMatrixResponse(nationId, rows))
    }
}
