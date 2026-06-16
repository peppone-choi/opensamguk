package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.NationFinanceDiplomacyState
import opensamguk.gameapi.dto.NationFinanceGoldIncome
import opensamguk.gameapi.dto.NationFinanceIncome
import opensamguk.gameapi.dto.NationFinanceNationItem
import opensamguk.gameapi.dto.NationFinancePolicy
import opensamguk.gameapi.dto.NationFinanceResponse
import opensamguk.gameapi.dto.NationFinanceRiceIncome
import opensamguk.gameapi.dto.NationFinanceWarSettingCnt
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.domestic.calcCityWarGoldIncome
import opensamguk.logic.domestic.getGoldIncome
import opensamguk.logic.domestic.getOutcome
import opensamguk.logic.domestic.getRiceIncome
import opensamguk.logic.domestic.getWallIncome
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.util.phpRound
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/nation/{id}/finance` (내무부, spec page 3). READ-only.
 *
 * W0-2(P0-51) — PHP `v_nationStratFinan.php:126-154` staticValues의 **중첩 shape**로 재구축:
 * `policy{rate,bill,secretLimit,blockScout,blockWar}` + `warSettingCnt{remain,inc,max}` +
 * `income{...}/outcome`(nullable) + `officerLevel/year/month`. 기존 평면 shape는 FE 타입(중첩)과
 * 불일치해 국가 소속자 전원 런타임 크래시였다.
 *
 * 원천(실데이터만 — 날조 금지):
 *  - gold/rice/level/name/color: `nation` 행 실 컬럼.
 *  - policy.rate/bill/secretLimit: `nation.meta`(NationFinanceSetters 실제 write 키) 방어적 read —
 *    미기재 시 null(P1-077 동일 규약: 시드가 아직 안 채움).
 *  - policy.blockWar/blockScout: `meta["war"]/["scout"] Int != 0` — W0-2(P0-53) read 키 정합
 *    (종전 metaBool("block_war")는 아무도 쓰지 않는 키였다).
 *  - warSettingCnt.inc/max: GameConst 실상수. remain: nation_env KV `available_war_setting_cnt`(W1-O 배선).
 *  - income/outcome: PHP rate=100 LIVE 산정(getGoldIncome/getWarGoldIncome/getRiceIncome/getWallIncome/
 *    getOutcome) — logic/domestic/IncomeTick.kt 패러티 함수를 read-side에서 도시/장수 실데이터로 재호출.
 *    비-RNG 결정식이라 골든 추가 없이 구성적 byte-parity(월틱 골든으로 이미 검증된 함수 재사용).
 *  - nationsList: getAllNationStaticInfo(전 국가) + cityCnt(city GROUP BY) + diplomacy{state,term}
 *    (자국 7/null, 타국 diplomacy WHERE me=id, 행 부재 시 통상=2 폴백).
 *  - nationMsg/scoutMsg: nation_env KV(nationNotice.msg / scout_msg). 부재 시 null.
 *  - year/month: world_state 클럭. officerLevel: 검증된 principal의 장수.
 *
 * `editable` = 호출자의 장수가 이 국가 소속 + officer_level >= 5 (수뇌). P1-084(ambassador
 * permission==4 동권한)는 W0-3 secretPermission 파운데이션과 함께 W1-O가 닫는다.
 * Missing nation → result:false zeroed shape (200, never 500).
 */
@RestController
@RequestMapping("/api/nation")
class NationFinanceController(
    private val nations: NationReadRepository,
    private val resolver: GeneralResolver,
    private val world: WorldStateReadRepository,
    private val nationEnv: NationEnvReadRepository,
    private val cities: CityReadRepository,
    private val generals: GeneralReadRepository,
    private val diplomacy: DiplomacyReadRepository,
    private val objectMapper: ObjectMapper,
) {

    /** nation_env(namespace = nationId, key) jsonb를 디코드 — 부재/파싱실패 시 null(날조 금지). */
    private fun nationEnvNode(nid: Int, key: String): JsonNode? =
        nationEnv.findByNamespaceAndKey(nid, key)?.let { runCatching { objectMapper.readTree(it.value) }.getOrNull() }

    @GetMapping("/{id}/finance")
    fun finance(
        @PathVariable id: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<NationFinanceResponse> {
        val resolved = userId?.let { resolver.resolve(it) }
        val officerLevel = resolved?.officerLevel ?: 0
        val w = world.findAll().firstOrNull()
        val warSetting = NationFinanceWarSettingCnt(
            // NF-P0-C — nation_env KV `available_war_setting_cnt`(데몬 SetBlockWar write). 부재 시 null(날조 금지).
            remain = nationEnvNode(id, "available_war_setting_cnt")?.takeIf { it.isNumber }?.asInt(),
            inc = GameConst.incAvailableWarSettingCnt,
            max = GameConst.maxAvailableWarSettingCnt,
        )

        val nation = nations.findById(id).orElse(null)
            ?: return ResponseEntity.ok(
                NationFinanceResponse(
                    result = false, nationId = id, name = "", color = "", level = 0,
                    officerLevel = 0, year = w?.currentYear ?: 0, month = w?.currentMonth ?: 0,
                    gold = 0, rice = 0,
                    policy = NationFinancePolicy(),
                    warSettingCnt = warSetting,
                    editable = false,
                ),
            )

        val meta = nation.meta
        fun metaInt(key: String) = (meta[key] as? Number)?.toInt()

        val editable = resolved != null && resolved.nationId == id && resolved.officerLevel >= 5

        // ── 수입/지출 LIVE 산정(PHP v_nationStratFinan.php:76-115) ──────────────────────────────────
        // PHP는 매 read마다 rate=100 기준으로 getGoldIncome/getWarGoldIncome/getRiceIncome/getWallIncome/
        // getOutcome을 LIVE 계산한다(저장값이 아니라 현재 도시/장수 상태의 what-if 미리보기). 동일 산정식은
        // 이미 logic/domestic/IncomeTick.kt에 패러티 포팅돼 있으므로(월틱 골든으로 검증됨), read-side에서
        // 동일 함수를 재사용하면 byte-parity가 구성적으로 보장된다 — 골든 추가 불필요(비-RNG 결정식).
        val cityList = cities.findByNationIdOrderByIdAsc(id).map { it.toLogic() }
        val nationType = NationTypeRegistry.resolve(nation.typeCode)
        // 소득 fold는 nation-type 소스만 사용(nationIncomeFold) — 빈 파이프라인이 정확(엔진과 동일 결과).
        val pipeline = GeneralActionPipeline()
        val capitalId = nation.capitalCityId ?: 0
        val nationGenerals = generals.findByNationIdOrderByOfficerLevelDescIdAsc(id)
        // PHP: SELECT officer_city, count(*) WHERE officer_level IN (2,3,4) AND city = officer_city GROUP BY.
        val officerCntByCity = HashMap<Int, Int>()
        for (g in nationGenerals) {
            if (g.officerLevel !in 2..4) continue
            if (g.cityId != g.officerCity) continue
            officerCntByCity[g.officerCity] = (officerCntByCity[g.officerCity] ?: 0) + 1
        }
        val taxRate = 100.0 // PHP는 항상 rate=100 미리보기로 표시한다(v_nationStratFinan.php:80,89,97).
        // getGoldIncome/Rice/Wall은 (반올림 정수 합)*(taxRate/20) Double 반환 — rate=100이면 정확한 정수배.
        val income = NationFinanceIncome(
            gold = NationFinanceGoldIncome(
                city = phpRound(getGoldIncome(cityList, capitalId, nation.level, taxRate, nationType, pipeline, officerCntByCity)),
                war = cityList.sumOf { calcCityWarGoldIncome(it, nationType, pipeline) },
            ),
            rice = NationFinanceRiceIncome(
                city = phpRound(getRiceIncome(cityList, capitalId, nation.level, taxRate, nationType, pipeline, officerCntByCity)),
                wall = phpRound(getWallIncome(cityList, capitalId, nation.level, taxRate, nationType, pipeline, officerCntByCity)),
            ),
        )
        // PHP getOutcome(100, dedicationList) — dedicationList = SELECT dedication WHERE nation AND npc != 5.
        val dedications = nationGenerals.filter { it.npcState != 5 }.map { it.dedication.toDouble() }
        val outcome = getOutcome(100.0, dedications)

        // ── nationsList(전 국가 외교 표, PHP :45-72 getAllNationStaticInfo + cityCnt + diplomacy) ──────
        // me = 이 페이지의 국가(id). 자국 state=7/term=null. 타국은 diplomacy WHERE me=id 행(없으면 통상=2).
        val dipByYou = diplomacy.findBySrcNationId(id).associateBy { it.destNationId }
        val nationsList = nations.findAll().filter { it.id != 0 }.sortedBy { it.id }.map { n ->
            val dip = if (n.id == id) {
                NationFinanceDiplomacyState(state = 7, term = null)
            } else {
                val d = dipByYou[n.id]
                // PHP는 행 존재를 가정한다(시나리오 시작 시 전 쌍 통상=2 시드). 행 부재 시 통상(2)으로 폴백.
                NationFinanceDiplomacyState(state = d?.stateCode ?: 2, term = d?.term)
            }
            NationFinanceNationItem(
                nation = n.id,
                name = n.name,
                color = n.color,
                type = n.typeCode,
                level = n.level,
                capital = n.capitalCityId ?: 0,
                gennum = (n.meta["gennum"] as? Number)?.toInt() ?: 0,
                power = n.power,
                cityCnt = cities.countByNationId(n.id).toInt(),
                diplomacy = dip,
            )
        }

        return ResponseEntity.ok(
            NationFinanceResponse(
                result = true,
                nationId = nation.id,
                name = nation.name,
                color = nation.color,
                level = nation.level,
                officerLevel = officerLevel,
                year = w?.currentYear ?: 0,
                month = w?.currentMonth ?: 0,
                gold = nation.gold,
                rice = nation.rice,
                // income/outcome — PHP rate=100 LIVE 산정(IncomeTick 패러티 재사용).
                income = income,
                outcome = outcome,
                policy = NationFinancePolicy(
                    rate = metaInt("rate"),
                    bill = metaInt("bill"),
                    secretLimit = metaInt("secretlimit"),
                    // P0-53 — 실제 write 키 meta["scout"]/["war"](Int), PHP `!= 0` 동식. 미기재 → null.
                    blockScout = metaInt("scout")?.let { it != 0 },
                    blockWar = metaInt("war")?.let { it != 0 },
                ),
                warSettingCnt = warSetting,
                // NF-P1-B — nation_env KV: nationNotice{msg}(국가 방침) / scout_msg(임관 권유문). 부재 시 null.
                nationMsg = nationEnvNode(nation.id, "nationNotice")?.get("msg")?.asText(),
                scoutMsg = nationEnvNode(nation.id, "scout_msg")?.asText(),
                nationsList = nationsList,
                editable = editable,
            ),
        )
    }
}
