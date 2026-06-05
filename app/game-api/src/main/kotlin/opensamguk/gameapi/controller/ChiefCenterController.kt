package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.ChiefCommand
import opensamguk.gameapi.dto.ChiefCommandCategory
import opensamguk.gameapi.dto.ChiefPost
import opensamguk.gameapi.dto.ChiefReservedResponse
import opensamguk.gameapi.dto.ChiefReservedTurn
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.NationTurnReadRepository
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.TurnTimeFormatter
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.actions.CommandRegistry
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
                commandList = buildChiefCommandTable(),
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
     * [AvailableCommandsController]와 동일한 알려진 flag(BLOCKED 아님):
     *  - `compensation` = 0 — PHP `getCompensationStyle()`(▲/▼)이 [opensamguk.logic.actions.GeneralActionDefinition]에 미포팅.
     *  - `possible` = true — PHP `hasMinConditionMet()`(최소조건)이 read 경로에 미연결(precheck 연동은 후속 wave).
     *  - PHP `canDisplay()` 필터(표시 가능 여부)도 미포팅 → 전 코드 표시(보수적). 후속 wave에서 좁힘.
     */
    private fun buildChiefCommandTable(): List<ChiefCommandCategory> =
        F4StateText.CHIEF_COMMAND_TABLE.map { (category, codes) ->
            ChiefCommandCategory(
                category = category,
                values = codes.map { code ->
                    val def = registry.resolve(code)
                    ChiefCommand(
                        value = def.key,
                        simpleName = def.name,
                        // 레지스트리에 별도 title 문자열이 없어 name을 표시명으로 사용
                        // (AvailableCommandsController와 동일 — PHP getCommandDetailTitle 미포팅 flag).
                        title = def.name,
                        compensation = 0,
                        possible = true,
                        reqArg = def.argsSchema.isNotEmpty(),
                    )
                },
            )
        }
}
