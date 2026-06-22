package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.ChiefCommand
import opensamguk.gameapi.dto.ChiefCommandCategory
import opensamguk.gameapi.dto.ChiefPost
import opensamguk.gameapi.dto.ChiefReservedResponse
import opensamguk.gameapi.dto.ChiefReservedTurn
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.NationTurnReadRepository
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.TurnTimeFormatter
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 / W3-ChiefCenter — `GET /api/nation/chief-reserved` (사령부, spec page 7). READ-only.
 *
 * PHP 원천: `API/NationCommand/GetReservedCommand.php`. 한 응답에 (1) 8개 직책 칸 + 직책별 예약
 * 국가 명령(`nation_turn` 링), (2) 호출자 식별, (3) 국가 컨텍스트, (4) 게임 시각, (5) 부대 목록,
 * (6) 사령부 명령 팔레트(`getChiefCommandTable`)를 함께 내려보낸다. W3에서 (2)~(6)을 보강하고
 * 예약 슬롯에 누락돼 있던 `arg`(구조화 인자)를 추가한다.
 *
 * 명령 팔레트(6)의 `possible`/`reason`은 이제 [AvailableCommandsController]와 동일하게 실제 precheck로
 * 배선된다([CommandPrecheckService] — 데몬과 동일 제약 라이브러리). 남은 알려진 flag는
 * `compensation`(getCompensationStyle ▲/▼ 미포팅)과 `canDisplay()` 필터 미포팅뿐이다.
 *
 * Identity-required(호출자의 국가는 검증된 principal에서 resolve — 국가별 권한 데이터). 캐릭터 없으면
 * 401. 재야(국가 없음)는 8개 칸을 빈 예약-턴 목록으로(200). 시드에 chief 행이 없으면 빈 목록(절대 500 X).
 *
 * 의도적 divergence: PHP는 `turn_idx`가 정규 범위(0..maxChiefTurn)를 벗어나면 `nation_turn`을 그 자리에서
 * UPDATE로 정규화한다(GetReservedCommand.php:103-116). 이 read 경로는 절대 쓰지 않는다(one-daemon-write-rule)
 * — 링 정규화는 데몬 write 경로 책임이다. 여기선 turn_idx를 그대로 표시만 한다.
 *
 * BLOCKED(W3_PLAN §2): `autorunLimit`(=PHP `autorun_limit`, general aux 원천 부재) → null로 둠.
 */
@RestController
@RequestMapping("/api/nation")
class ChiefCenterController(
    private val resolver: GeneralResolver,
    private val nationTurns: NationTurnReadRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val world: WorldStateReadRepository,
    private val troops: TroopReadRepository,
    private val registry: CommandRegistry,
    private val precheck: CommandPrecheckService,
) {

    /** Legacy 예약-턴 cap(maxChiefTurn). 30-턴 링이 국가 명령 max(`GameConst::$maxChiefTurn`). */
    private val maxChiefTurn = 12

    @GetMapping("/chief-reserved")
    fun chiefReserved(@AuthenticationPrincipal userId: Long?): ResponseEntity<ChiefReservedResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val nationId = resolved.nationId
        val nationLevel = resolved.nationLevel

        // 1) 직책별 예약 국가 명령(officer_level → 슬롯 목록). 재야면 비어 있음.
        //    PHP: `nation_turn` ORDER BY officer_level DESC, turn_idx ASC → groupBy(officer_level).
        val turnsByLevel: Map<Int, List<ChiefReservedTurn>> = if (nationId != 0) {
            nationTurns.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId)
                .groupBy { it.officerLevel }
                .mapValues { (_, rows) ->
                    rows.sortedBy { it.turnIdx }
                        .map {
                            ChiefReservedTurn(
                                turnIdx = it.turnIdx,
                                actionCode = it.actionCode,
                                brief = it.brief,
                                arg = it.arg,
                            )
                        }
                }
        } else {
            emptyMap()
        }

        // 2) 직책 보유 장수(officer_level >= 5) 1회 조회 후 officer_level로 색인.
        //    PHP: `SELECT ... FROM general WHERE nation = %i AND officer_level >= 5`.
        //    동일 레벨에 다수가 있으면 안 되지만(직책=유니크), 방어적으로 첫 행을 채택.
        val generalsByLevel: Map<Int, GeneralReadEntity> = if (nationId != 0) {
            generals.findByNationIdOrderByOfficerLevelDescIdAsc(nationId)
                .filter { it.officerLevel >= 5 }
                .associateBy { it.officerLevel }
        } else {
            emptyMap()
        }

        // 3) 8개 직책 칸을 materialize. 공석이면 name/turnTime/npcType = null.
        val posts = F4StateText.CHIEF_POSTS.map { meta ->
            val general = generalsByLevel[meta.officerLevel]
            ChiefPost(
                officerLevel = meta.officerLevel,
                title = meta.title,
                name = general?.name,
                // 공유 foundation F3 포매터(ChiefCenter + GeneralList) — PHP getTurnTime(TURNTIME_FULL).
                turnTime = general?.let { TurnTimeFormatter.full(it.turnTime) },
                npcType = general?.npcState,
                officerLevelText = F4StateText.officerLevelText(meta.officerLevel, nationLevel),
                reservedTurns = turnsByLevel[meta.officerLevel] ?: emptyList(),
            )
        }

        // 4) 게임 시각(world_state 클럭). FrontInfoController와 동일 좌표계(turnterm = tickSeconds/60).
        val w = world.findAll().firstOrNull()
        val year = w?.currentYear ?: 0
        val month = w?.currentMonth ?: 0
        val turnTerm = (w?.tickSeconds ?: 0) / 60

        // 5) 국가명(재야 fallback). nationLevel은 resolver에서 이미 로드됨.
        val nationName = if (nationId != 0) {
            nations.findById(nationId).map { it.name }.orElse(F4StateText.NEUTRAL_NATION_NAME)
        } else {
            F4StateText.NEUTRAL_NATION_NAME
        }

        // 6) 부대 목록(troop_leader → name). 시드 무행이면 빈 맵.
        val troopList: Map<String, String> = if (nationId != 0) {
            troops.findByNationOrderByTroopLeaderAsc(nationId)
                .associate { it.troopLeader.toString() to it.name }
        } else {
            emptyMap()
        }

        return ResponseEntity.ok(
            ChiefReservedResponse(
                result = true,
                myGeneralId = resolved.general.id,
                myOfficerLevel = resolved.officerLevel,
                nationId = nationId,
                nationName = nationName,
                nationLevel = nationLevel,
                year = year,
                month = month,
                turnTerm = turnTerm,
                maxChiefTurn = maxChiefTurn,
                posts = posts,
                troopList = troopList,
                commandList = buildChiefCommandTable(resolved.general.id),
                isChief = resolved.officerLevel > 4,
                // BLOCKED(§2): autorun_limit 원천 부재 → null(날조 금지).
                autorunLimit = null,
            ),
        )
    }

    /**
     * 사령부 명령 팔레트 — PHP `getChiefCommandTable(General)` (func.php:481-513) 포팅.
     *
     * `F4StateText.CHIEF_COMMAND_TABLE`(= `GameConst::$availableChiefCommand`)의 6개 카테고리/코드를
     * 공유 [CommandRegistry]로 풀어 표시 메타(`value/simpleName/title/reqArg`)를 만든다 — 코드/이름/인자필요
     * 여부는 모두 레지스트리 정의에서 가져오며 날조하지 않는다.
     *
     * `possible`/`reason`은 이제 [AvailableCommandsController]와 동일하게 **실제 precheck 결과**로 채운다
     * ([CommandPrecheckService.precheckAll], precheck == full): 데몬이 돌리는 동일 제약 라이브러리를
     * 마지막 flush된 DB 행 위에서 read-only로 평가한다 — 더 이상 고정 true 기본값이 아니다.
     * 호출자(직책 보유 장수) 행이 없으면 레지스트리-only 카탈로그로 폴백한다(`possible=true`).
     *
     * 남은 알려진 flag(BLOCKED 아님, AvailableCommandsController와 동일):
     *  - `compensation` = 0 — PHP `getCompensationStyle()`(▲/▼)이 [GeneralActionDefinition]에 미포팅.
     *  - PHP `canDisplay()` 필터(표시 가능 여부)도 미포팅 → 전 코드 표시(보수적). 후속 wave에서 좁힘.
     */
    private fun buildChiefCommandTable(actingGeneralId: Int): List<ChiefCommandCategory> {
        // 모든 사령부 코드를 공유 레지스트리로 1회만 해소(code → def). precheck/표시 메타 모두 이 맵을 재사용.
        val defByCode: Map<String, GeneralActionDefinition> =
            F4StateText.CHIEF_COMMAND_TABLE.flatMap { (_, codes) -> codes }
                .associateWith { registry.resolve(it) }
        // 호출자 행이 있으면 실제 precheck(possible/reason), 없으면 null → 레지스트리-only 폴백.
        // precheckAll은 def.key(== code)로 키한다 → results[code]로 조회.
        val results: Map<String, PrecheckResult>? =
            precheck.precheckAll(actingGeneralId, defByCode.values.toList())

        return F4StateText.CHIEF_COMMAND_TABLE.map { (category, codes) ->
            ChiefCommandCategory(
                category = category,
                values = codes.map { code ->
                    val def = defByCode.getValue(code)
                    val reqArg = def.argsSchema.isNotEmpty()
                    // AvailableCommandsController.toRow와 동일한 (possible, reason) 산출 로직.
                    val (possible, reason) = when (val result = results?.get(code)) {
                        null -> true to null // 액터 없음(또는 카탈로그 폴백) → 레지스트리-only, deny reason 없음
                        PrecheckResult.Available -> true to null
                        is PrecheckResult.Blocked -> false to result.reason
                        is PrecheckResult.Unknown ->
                            // precheck가 부재 요건에 막힘: 인자 명령은 대상만 고르면 됨(possible),
                            // 인자 없는 명령은 여기서 판정 불가 → UNKNOWN_REASON.
                            if (reqArg) true to null else false to UNKNOWN_REASON
                    }
                    ChiefCommand(
                        value = def.key,
                        simpleName = def.name,
                        // 레지스트리에 별도 title 문자열이 없어 name을 표시명으로 사용
                        // (AvailableCommandsController와 동일 — PHP getCommandDetailTitle 미포팅 flag).
                        title = def.name,
                        compensation = 0,
                        possible = possible,
                        reqArg = reqArg,
                        // 인자 폼 타입을 argsSchema 키에서 파생 — AvailableCommandsController와 동일 규칙(날조 아님).
                        argType = argTypeOf(def.argsSchema.keys),
                        reason = reason,
                    )
                },
            )
        }
    }

    /** 명령의 `argsSchema` 키에서 모달 필드 타입을 파생(AvailableCommandsController.argTypeOf 정본 미러). */
    private fun argTypeOf(keys: Set<String>): String? = when {
        "nationName" in keys && "nationType" in keys && "colorType" in keys -> "founding"
        "crewType" in keys && "amount" in keys -> "recruit"
        "destCityID" in keys -> "city"
        "destNationID" in keys -> "nation"
        "destGeneralID" in keys -> "general"
        "amount" in keys -> "amount"
        else -> null
    }

    companion object {
        // AvailableCommandsController.UNKNOWN_REASON과 동일 상수("정보 부족").
        private const val UNKNOWN_REASON = "정보 부족"
    }
}
