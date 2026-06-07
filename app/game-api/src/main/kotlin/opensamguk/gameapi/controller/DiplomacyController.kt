package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.DiplomacyConflictResponse
import opensamguk.gameapi.dto.DiplomacyLetter
import opensamguk.gameapi.dto.DiplomacyLetterParty
import opensamguk.gameapi.dto.DiplomacyLettersResponse
import opensamguk.gameapi.dto.DiplomacyMatrixResponse
import opensamguk.gameapi.dto.DiplomacyNationInfo
import opensamguk.gameapi.dto.DiplomacyResponse
import opensamguk.gameapi.dto.SimpleNationObj
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
import java.math.BigDecimal
import java.math.RoundingMode

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
            n.id.toString() to DiplomacyNationInfo(id = n.id, name = n.name, color = n.color, level = n.level)
        }
        val resolved = userId?.let { resolver.resolve(it) }
        val myNationID = resolved?.nationId ?: 0
        // PHP `checkSecretPermission($me)` 호출자 외교 권한(0~4). detail 마스킹 게이트(<3)에 사용.
        // officer_level 분기만 충실 모델(아래 secretPermission). ambassador/auditor/secretlimit 분기는 BLOCKED.
        val mySecretPermission = secretPermission(resolved)
        val letterRows = if (myNationID != 0) {
            letters.findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(myNationID, myNationID)
                // PHP `WHERE ... AND state != 'cancelled'`(j_diplomacy_get_letter.php:46) — 거부/파기된 서신은 목록 제외.
                .filter { !it.state.equals("CANCELLED", ignoreCase = true) }
                .map { l ->
                    val srcNation = nationMap[l.srcNationId.toString()]
                    val destNation = nationMap[l.destNationId.toString()]
                    // PHP detail 마스킹: permission<3 이면 본문을 '(권한이 부족합니다)'로 치환(빈 본문은 그대로 빈 채).
                    // (j_diplomacy_get_letter.php:51-53 — `if($permission < 3 && $letter['text_detail'])`).
                    val detail = if (mySecretPermission < 3 && l.textDetail.isNotEmpty()) {
                        DETAIL_MASK
                    } else {
                        l.textDetail
                    }
                    DiplomacyLetter(
                        no = l.id,
                        // PHP는 aux['src']/aux['dest'] 스냅샷을 내려보내고 nationID만 컬럼값으로 덮어쓴다
                        // (j_diplomacy_get_letter.php:55-59). nationName/Color는 aux 우선, 부재 시 nation 조회 폴백.
                        src = letterParty(l.aux["src"], l.srcNationId, srcNation),
                        dest = letterParty(l.aux["dest"], l.destNationId, destNation),
                        prevNo = l.prevId,
                        state = l.state.lowercase(),
                        stateText = F4StateText.letterStateText(l.state),
                        stateOpt = extractStateOpt(l.aux),
                        brief = l.textBrief,
                        detail = detail,
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

    private fun extractStateOpt(aux: Map<String, Any?>): String? =
        aux["state_opt"] as? String

    /**
     * 서신 1 당사자 구성. PHP는 `aux['src']`/`aux['dest']`(INSERT 시점 스냅샷)를 그대로 쓰되 `nationID`만
     * 컬럼값으로 덮는다(j_diplomacy_get_letter.php:55-59). nationName/nationColor는 aux 스냅샷 우선,
     * 부재(legacy 데이터 결손) 시 현재 nation 조회로 폴백. generalName/generalIcon은 aux에만 존재(없으면 null).
     *
     * @param auxParty `aux['src']`(또는 `aux['dest']`) — 보통 Map, 결손 시 null.
     * @param nationId 컬럼값(`src_nation_id`/`dest_nation_id`) — nationID를 항상 덮어쓴다.
     * @param nationFallback 해당 국가의 현재 name/color(aux 스냅샷 결손 폴백).
     */
    @Suppress("UNCHECKED_CAST")
    private fun letterParty(
        auxParty: Any?,
        nationId: Int,
        nationFallback: DiplomacyNationInfo?,
    ): DiplomacyLetterParty {
        val party = auxParty as? Map<String, Any?> ?: emptyMap()
        return DiplomacyLetterParty(
            nationId = nationId,
            nationName = (party["nationName"] as? String) ?: nationFallback?.name ?: "",
            nationColor = (party["nationColor"] as? String) ?: nationFallback?.color ?: "#000000",
            generalName = party["generalName"] as? String,
            generalIcon = party["generalIcon"] as? String,
        )
    }

    /**
     * PHP `checkSecretPermission($me)`의 officer_level 분기 충실 포팅(0~4 비밀 권한).
     *  - nation 없음 또는 officer_level==0 → -1(소속 없음/일반).
     *  - officer_level==12(군주) → 4.
     *  - officer_level>=5(수뇌) → 2.
     *  - officer_level>1(관직자) → 1.
     *  - 그 외 → 0.
     * BLOCKED(백로그): `permission` enum 컬럼(ambassador 외교권→4 / auditor 감찰권→3),
     * `secretlimit`+`belong` 분기, `penalty[NoChief]→0`, `checkSecretMaxPermission` 상한 —
     * 모두 opensamguk general 스키마에 원천 컬럼이 없어(P8 미이식) 재현 불가. 값 날조 금지.
     * 따라서 현 모델에선 detail(<3) 마스킹 해제는 군주(lv12=4)만 가능하다 — divergence로 표기.
     */
    private fun secretPermission(resolved: GeneralResolver.ResolvedGeneral?): Int {
        if (resolved == null || resolved.nationId == 0) return -1
        val officerLevel = resolved.officerLevel
        return when {
            officerLevel == 0 -> -1
            officerLevel == 12 -> 4
            officerLevel >= 5 -> 2
            officerLevel > 1 -> 1
            else -> 0
        }
    }

    private companion object {
        /** PHP detail 마스킹 문자열 verbatim(j_diplomacy_get_letter.php:52). */
        const val DETAIL_MASK = "(권한이 부족합니다)"
    }

    /**
     * D11 — `GET /api/diplomacy/conflict` = PHP `GetDiplomacy.php:26-104`. READ-only, GAME_LOGIN.
     *
     * 봉투 `{result, nations[], conflict[[cityId,{nationId:pct}]], diplomacyList{me:{you:state}}, myNationID}`:
     *  - nations = getAllNationStaticInfo() level>0 필터 + power DESC(uasort -power) + 도시명(행 순서) 보강.
     *  - conflict = city.conflict('{}'/항목<2 제외) → round(100*killnum/sum, 1) PhpRound half-away Double.
     *  - diplomacyList = 모든 diplomacy 행 me→you→state, viewer-conditional 마스킹(me/you 둘 다 내 국가가
     *    아닐 때만 3..7→2; 한쪽이 내 국가면 원 state).
     *  - myNationID = 호출자 장수의 nation(미인증/재야면 0).
     */
    @GetMapping("/conflict")
    fun conflict(@AuthenticationPrincipal userId: Long?): ResponseEntity<DiplomacyConflictResponse> {
        val myNationID = userId?.let { resolver.resolve(it)?.nationId } ?: 0

        // nations — getAllNationStaticInfo()의 level>0만, power DESC 정렬(PHP array_filter + uasort -power).
        // 도시명 보강을 위해 nationId → 가변 도시 리스트를 따로 둔다(삽입순서 = city 행 순서).
        val nationCities = linkedMapOf<Int, MutableList<String>>()
        val sortedNations = nations.findAll()
            .filter { it.level > 0 }
            .sortedByDescending { it.power }
        for (n in sortedNations) {
            nationCities[n.id] = mutableListOf()
        }

        // city 행 1회 순회로 (a) 도시명 그룹 보강(nationId!=0) (b) 분쟁 % 산출.
        // PHP: SELECT nation, city, name, conflict FROM city — 행 순서 보존.
        val conflict = mutableListOf<List<Any>>()
        for (c in cities.findAll()) {
            if (c.nationId != 0) {
                nationCities[c.nationId]?.add(c.name)
            }
            // city.conflict = nationId→killnum jsonb. '{}'(빈맵) 또는 항목<2면 제외(PHP:59-65).
            if (c.conflict.size < 2) {
                continue
            }
            val sum = c.conflict.values.sumOf { (it as? Number)?.toDouble() ?: 0.0 }
            if (sum == 0.0) {
                continue
            }
            // pct = round(100 * killnum / sum, 1) — PHP 네이티브 round half-away-from-zero, 소수1자리 Double.
            val pctMap = linkedMapOf<String, Double>()
            for ((nk, nv) in c.conflict) {
                val killnum = (nv as? Number)?.toDouble() ?: 0.0
                pctMap[nk] = BigDecimal.valueOf(100.0 * killnum / sum)
                    .setScale(1, RoundingMode.HALF_UP)
                    .toDouble()
            }
            conflict.add(listOf(c.id, pctMap))
        }

        val nationObjs = sortedNations.map { n ->
            SimpleNationObj(
                nation = n.id,
                name = n.name,
                color = n.color,
                type = n.typeCode,
                level = n.level,
                capital = n.capitalCityId ?: 0,
                gennum = (n.meta["gennum"] as? Number)?.toInt() ?: 0,
                cities = nationCities[n.id] ?: emptyList(),
                power = n.power,
            )
        }

        // diplomacyList — 모든 diplomacy 행을 me→you→state로. viewer-conditional 마스킹(PHP:91-95).
        val diplomacyList = linkedMapOf<String, MutableMap<String, Int>>()
        for (r in diplomacyReadRepository.findAll()) {
            val me = r.srcNationId
            val you = r.destNationId
            val state = if (me != myNationID && you != myNationID && r.stateCode in 3..7) {
                2
            } else {
                r.stateCode
            }
            diplomacyList.getOrPut(me.toString()) { linkedMapOf() }[you.toString()] = state
        }

        return ResponseEntity.ok(
            DiplomacyConflictResponse(
                result = true,
                nations = nationObjs,
                conflict = conflict,
                diplomacyList = diplomacyList,
                myNationID = myNationID,
            ),
        )
    }
}
