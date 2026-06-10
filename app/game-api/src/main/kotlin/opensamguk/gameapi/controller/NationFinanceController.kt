package opensamguk.gameapi.controller

import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.NationFinancePolicy
import opensamguk.gameapi.dto.NationFinanceResponse
import opensamguk.gameapi.dto.NationFinanceWarSettingCnt
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
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
 *  - warSettingCnt.inc/max: GameConst 실상수. remain: nation_env KV — read repo 부재로 null
 *    (P0-53 BLOCKED, W1-O가 NationEnvReadRepository 신설 후 배선).
 *  - income/outcome: read 경로에 nation-type income 파이프라인 미조립(P0-52 BLOCKED) → null.
 *    종전 metaInt("income"/"outcome")는 기록 주체가 없는 위조 0이라 제거.
 *  - nationMsg/scoutMsg: nation_env KV(P0-53 BLOCKED) → null. 종전 meta read는 잘못된 스토어.
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
) {

    @GetMapping("/{id}/finance")
    fun finance(
        @PathVariable id: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<NationFinanceResponse> {
        val resolved = userId?.let { resolver.resolve(it) }
        val officerLevel = resolved?.officerLevel ?: 0
        val w = world.findAll().firstOrNull()
        val warSetting = NationFinanceWarSettingCnt(
            remain = null, // P0-53 BLOCKED — nation_env KV read repo 부재(W1-O 배선).
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
                // income/outcome — P0-52 BLOCKED(read income 파이프라인 미조립) → null(위조 0 제거).
                income = null,
                outcome = null,
                policy = NationFinancePolicy(
                    rate = metaInt("rate"),
                    bill = metaInt("bill"),
                    secretLimit = metaInt("secretlimit"),
                    // P0-53 — 실제 write 키 meta["scout"]/["war"](Int), PHP `!= 0` 동식. 미기재 → null.
                    blockScout = metaInt("scout")?.let { it != 0 },
                    blockWar = metaInt("war")?.let { it != 0 },
                ),
                warSettingCnt = warSetting,
                // nationMsg/scoutMsg/nationsList — P0-53/P0-54 BLOCKED(W1-O 배선) → null.
                nationMsg = null,
                scoutMsg = null,
                nationsList = null,
                editable = editable,
            ),
        )
    }
}
