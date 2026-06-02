package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.CityConflict
import opensamguk.gameapi.dto.DiplomacyConflictResponse
import opensamguk.gameapi.dto.DiplomacyLetter
import opensamguk.gameapi.dto.DiplomacyLettersResponse
import opensamguk.gameapi.dto.DiplomacyMatrixResponse
import opensamguk.gameapi.dto.DiplomacyNationInfo
import opensamguk.gameapi.dto.DiplomacyResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyLetterReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/diplomacy")
class DiplomacyController(
    private val diplomacyReadRepository: DiplomacyReadRepository,
    private val letters: DiplomacyLetterReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val resolver: GeneralResolver,
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

    /**
     * F4 — 외교부 (diplomacy letters), spec page 1. READ-only.
     *
     * Returns the nations name/color map + the calling nation's letter feed (sent OR received) +
     * `myNationID`. Identity is OPTIONAL: when a verified principal resolves to a general, `myNationID`
     * is that general's nation and the letters are scoped to it; otherwise (anonymous / no character)
     * `myNationID` is 0 and `letters` is empty (graceful — never 500). `diplomacy_letter` EXISTS but
     * carries no rows in the fresh seed → empty letters. State text is verbatim 제안됨/승인됨/거부됨/대체됨.
     */
    @GetMapping("/letters")
    fun letters(@AuthenticationPrincipal userId: Long?): ResponseEntity<DiplomacyLettersResponse> {
        val nationMap = nations.findAll().associate { n ->
            n.id.toString() to DiplomacyNationInfo(id = n.id, name = n.name, color = n.color)
        }
        val myNationID = userId?.let { resolver.resolve(it)?.nationId } ?: 0
        val letterRows = if (myNationID != 0) {
            letters.findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(myNationID, myNationID).map { l ->
                DiplomacyLetter(
                    id = l.id,
                    srcNationId = l.srcNationId,
                    destNationId = l.destNationId,
                    prevId = l.prevId,
                    state = l.state,
                    stateText = F4StateText.letterStateText(l.state),
                    textBrief = l.textBrief,
                    textDetail = l.textDetail,
                    date = l.date,
                    srcSigner = l.srcSigner,
                    destSigner = l.destSigner,
                )
            }
        } else {
            emptyList()
        }
        return ResponseEntity.ok(
            DiplomacyLettersResponse(
                result = true,
                myNationID = myNationID,
                nations = nationMap,
                letters = letterRows,
            ),
        )
    }

    /**
     * F4 — 중원정보 conflict feed (분쟁), spec page 2. READ-only, PUBLIC.
     *
     * Per-city 분쟁% from the `city.conflict` jsonb (an insertion-ordered nationId→percent map; the
     * ConquerCity/city-conflict side-effect writes it). Cities with no conflict map yield an empty
     * `conflict` object. Plus the global diplomacy matrix (srcNation → destNation → masked stateCode,
     * same 3~7→2 neutral masking as `/{nationId}`). No conflict rows in the seed → empty maps, 200.
     */
    @GetMapping("/conflict")
    fun conflict(): ResponseEntity<DiplomacyConflictResponse> {
        val cityRows = cities.findAll()
            .sortedBy { it.id }
            .map { c ->
                // city.conflict is the dedicated jsonb column — an insertion-ordered nationId→percent
                // map (LinkedHashMap from the byte-faithful decoder). Empty {} when no conflict.
                val conflictMap = linkedMapOf<String, Int>()
                for ((nk, nv) in c.conflict) {
                    (nv as? Number)?.let { conflictMap[nk] = it.toInt() }
                }
                CityConflict(cityId = c.id, cityName = c.name, conflict = conflictMap)
            }

        val matrix = linkedMapOf<String, Map<String, Int>>()
        for (n in nations.findAll().sortedBy { it.id }) {
            val rels = linkedMapOf<String, Int>()
            for (r in diplomacyReadRepository.findBySrcNationId(n.id)) {
                rels[r.destNationId.toString()] = if (r.stateCode in 3..7) 2 else r.stateCode
            }
            matrix[n.id.toString()] = rels
        }

        return ResponseEntity.ok(DiplomacyConflictResponse(result = true, cities = cityRows, matrix = matrix))
    }
}
