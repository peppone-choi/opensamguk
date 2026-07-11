package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.GeneralListEnv
import opensamguk.gameapi.dto.GeneralListResponse
import opensamguk.gameapi.dto.GeneralListRow
import opensamguk.gameapi.dto.GeneralListTroop
import opensamguk.gameapi.dto.ReservedCommandItem
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralListColumns
import opensamguk.gameapi.read.GeneralListText
import opensamguk.gameapi.read.GeneralAccessLogReadEntity
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.TurnTimeFormatter
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.getBillByLevel
import opensamguk.logic.domestic.getDedLevel
import opensamguk.logic.domestic.getDedLevelText
import opensamguk.logic.domestic.getExpLevel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * W3 — `GET /api/nation/general-list` (세력 장수 목록). PHP `API/Nation/GeneralList.php`의 충실 이식.
 *
 * 신규 컨트롤러(disjoint) — 기존 read 컨트롤러를 co-widen하지 않고 W3 Wave-0 foundation(F1 신규 컬럼
 * 매핑, F2 RankDataReadRepository, F3 TurnTimeFormatter)만 **소비**한다.
 *
 * Identity-required(per-nation privileged data) — 인증 principal로 호출자의 장수/국가/권한을 해소한다.
 * 권한 tier로 컬럼을 필터(P0/P1)하고 행을 `column` 순서의 flat array로 평탄화하는 PHP 계약을 그대로 따른다.
 *  - 캐릭터 없음 → 401
 *  - 재야(국가 없음) → 빈 목록(200) + P0 컬럼만(permission 0)
 * 전부 read-only — game-state write 없음.
 *
 * §2 BLOCKED(원천 없음 → 컬럼 자체를 노출하지 않음, fabricate 금지):
 *  - dex1..dex5(general 컬럼/meta 미존재), defence_train(원천 미검증), autorun_limit(컬럼 부재).
 *    PHP viewColumns에는 있으나 opensamguk
 *    원천이 없어 `column` 목록에서 제외한다. ownerName은 컬럼으로는 유지하되 항상 null(owner_name 부재).
 */
@RestController
@RequestMapping("/api/nation")
class GeneralListController(
    private val resolver: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val generalTurns: GeneralTurnReadRepository,
    private val rankData: RankDataReadRepository,
    private val troops: TroopReadRepository,
    private val world: WorldStateReadRepository,
    private val accessLogs: GeneralAccessLogReadRepository? = null,
) {

    @GetMapping("/general-list")
    fun generalList(@AuthenticationPrincipal userId: Long?): ResponseEntity<GeneralListResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val nationId = resolved.nationId
        val permission = resolved.permission
        val myGeneralId = resolved.general.id
        val env = buildEnv()

        // 재야(국가 없음): PHP는 nation=0 SELECT → 빈 목록. P0 컬럼만 노출(permission 0).
        if (nationId == 0) {
            val columns = GeneralListColumns.columnsForPermission(0)
            return ResponseEntity.ok(
                GeneralListResponse(
                    result = true,
                    permission = 0,
                    column = columns,
                    list = emptyList(),
                    troops = emptyList(),
                    env = env,
                    myGeneralID = myGeneralId,
                ),
            )
        }

        // 국가 레벨(officerLevelText/lbonus 계산 입력). 국가 부재 시 0.
        val nationLevel = nations.findById(nationId).map { it.level }.orElse(0)

        // 정렬: PHP ORDER BY turntime ASC.
        val rawGenerals = generals.findByNationIdOrderByTurnTimeAsc(nationId)

        // rank_data(F2) — 이 국가 장수들의 전투 통계 6종을 1회 일괄 조회 후 (generalId,type)로 색인.
        val generalIds = rawGenerals.map { it.id }
        val accessByGeneral = if (generalIds.isEmpty()) {
            emptyMap()
        } else {
            accessLogs?.findByGeneralIdIn(generalIds)?.associateBy { it.generalId }.orEmpty()
        }
        val rankByGeneral: Map<Pair<Int, String>, Int> =
            if (generalIds.isEmpty()) {
                emptyMap()
            } else {
                rankData.findByGeneralIdsAndTypes(generalIds, GeneralListColumns.RANK_TYPES)
                    .associate { (it.generalId to it.type) to it.value }
            }

        // 부대(troop) — 부대장 id가 이 국가 장수 목록에 있는 것만(PHP key_exists 가드).
        val generalById = rawGenerals.associateBy { it.id }
        val troopRows = troops.findByNationOrderByTroopLeaderAsc(nationId)
            .filter { generalById.containsKey(it.troopLeader) }

        // reservedCommand — permission >= 1 이거나 부대가 있을 때만 조회(PHP:199).
        //  - permission >= 1: npc_state < 2 인 모든 장수가 대상.
        //  - 부대: 부대장 id도 대상에 추가.
        val reservedTargets = LinkedHashSet<Int>()
        if (permission >= 1) {
            rawGenerals.filter { it.npcState < 2 }.forEach { reservedTargets.add(it.id) }
        }
        troopRows.forEach { reservedTargets.add(it.troopLeader) }

        val reservedByGeneral: Map<Int, List<ReservedCommandItem>> =
            if (reservedTargets.isEmpty()) {
                emptyMap()
            } else {
                generalTurns.findReservedByGeneralIds(reservedTargets)
                    .groupBy { it.generalId }
                    .mapValues { (_, slots) ->
                        slots.map { ReservedCommandItem(action = it.actionCode, arg = it.arg, brief = it.brief) }
                    }
            }

        // 각 장수를 P0/P1 채운 중간 표현으로(officerLevelText는 호출자 permission에 의존하는 마스킹 사용).
        val rows = rawGenerals.map { g ->
            buildRow(g, nationLevel, permission, rankByGeneral, reservedByGeneral[g.id], accessByGeneral[g.id])
        }

        // 컬럼 순서대로 flat array 평탄화.
        val columns = GeneralListColumns.columnsForPermission(permission)
        val flatList = rows.map { row -> columns.map { col -> GeneralListColumns.cell(row, col, permission) } }

        // 부대 응답 — reservedCommand의 brief를 '집합'/'-'로 매핑(PHP:303-309).
        val troopDtos = troopRows.map { t ->
            val leader = generalById.getValue(t.troopLeader)
            val leaderReserved = reservedByGeneral[t.troopLeader].orEmpty()
            GeneralListTroop(
                id = t.troopLeader,
                name = t.name,
                turntime = TurnTimeFormatter.full(leader.turnTime),
                reservedCommand = leaderReserved.map { if (it.brief == "집합") "집합" else "-" },
            )
        }

        return ResponseEntity.ok(
            GeneralListResponse(
                result = true,
                permission = permission,
                column = columns,
                list = flatList,
                troops = troopDtos,
                env = env,
                myGeneralID = myGeneralId,
            ),
        )
    }

    /** general 행 → P0/P1 채운 [GeneralListRow]. meta-derived/computed/rank 전부 여기서 산출. */
    private fun buildRow(
        g: GeneralReadEntity,
        nationLevel: Int,
        permission: Int,
        rankByGeneral: Map<Pair<Int, String>, Int>,
        reserved: List<ReservedCommandItem>?,
        accessLog: GeneralAccessLogReadEntity?,
    ): GeneralListRow {
        val meta = g.meta
        // PHP officerLevelText = getOfficerLevelText(getOfficerLevel($rawGeneral), nationLevel)
        // (GeneralList.php:261) — 마스킹된 officer_level을 쓴다(호출자 permission 의존).
        val maskedLevel = GeneralListColumns.getOfficerLevel(g.officerLevel, permission)
        // explevel/dedlevel은 엔진이 checkStatChange로 meta에 기록하는 STORED 값(StatChange.kt:103/133).
        // PHP도 general 행의 저장된 dedlevel/explevel 컬럼을 그대로 읽으므로 meta를 1차 원천으로 한다.
        // meta에 아직 없으면(예: checkStatChange 미수행 신규 seed) raw stat에서 재계산한 값으로 보정
        // (getExpLevel/getDedLevel — 엔진이 쓰는 것과 동일 공식이라 패러티 손실 없음).
        val explevel = if (meta.containsKey("explevel")) metaInt(meta, "explevel") else getExpLevel(g.experience.toDouble())
        val dedlevel = if (meta.containsKey("dedlevel")) metaInt(meta, "dedlevel") else getDedLevel(g.dedication.toDouble())
        return GeneralListRow(
            // P0
            no = g.id,
            name = g.name,
            nation = g.nationId,
            npc = g.npcState,
            injury = g.injury,
            leadership = g.leadership,
            strength = g.strength,
            intel = g.intel,
            explevel = explevel,
            dedlevel = dedlevel,
            gold = g.gold,
            rice = g.rice,
            killturn = (meta["killturn"] as? Number)?.toInt(), // KV 미배선 시 null(§2)
            picture = g.picture,
            imgsvr = g.imageServer,
            age = g.age,
            specialDomestic = g.specialCode,
            specialWar = g.special2Code,
            personal = g.personalCode,
            belong = metaInt(meta, "belong"),
            troop = g.troopId,
            city = g.cityId,
            specage = metaInt(meta, "specage"),
            specage2 = metaInt(meta, "specage2"),
            // P1
            leadershipExp = (meta["leadership_exp"] as? Number)?.toDouble() ?: 0.0,
            strengthExp = (meta["strength_exp"] as? Number)?.toDouble() ?: 0.0,
            intelExp = (meta["intel_exp"] as? Number)?.toDouble() ?: 0.0,
            experience = g.experience,
            dedication = g.dedication,
            officerLevel = g.officerLevel,
            officerCity = g.officerCity,
            crewtype = g.crewTypeId,
            crew = g.crew,
            train = g.train,
            atmos = g.atmos,
            turntime = TurnTimeFormatter.full(g.turnTime),
            horse = g.horseCode,
            weapon = g.weaponCode,
            book = g.bookCode,
            item = g.itemCode,
            recentWar = TurnTimeFormatter.full(g.recentWarTime),
            refreshScoreTotal = accessLog?.refreshScoreTotal ?: 0,
            refreshScore = accessLog?.refreshScore ?: 0,
            // RANK(F2) — 미기록 type은 0(PHP `?? 0`).
            warnum = rankByGeneral[g.id to "warnum"] ?: 0,
            killnum = rankByGeneral[g.id to "killnum"] ?: 0,
            deathnum = rankByGeneral[g.id to "deathnum"] ?: 0,
            killcrew = rankByGeneral[g.id to "killcrew"] ?: 0,
            deathcrew = rankByGeneral[g.id to "deathcrew"] ?: 0,
            firenum = rankByGeneral[g.id to "firenum"] ?: 0,
            // computed
            officerLevelText = GeneralListText.officerLevelText(maskedLevel, nationLevel),
            lbonus = calcLeadershipBonus(g.officerLevel, nationLevel),
            ownerName = null, // §2 BLOCKED — owner_name 원천 부재(npc==1 케이스도 미보유)
            honorText = GeneralListText.honor(g.experience),
            // PHP: getDedLevelText($rawGeneral['dedlevel']) / getBillByLevel($rawGeneral['dedlevel'])
            // — STORED dedlevel(위에서 산출)을 쓴다(dedication에서 매번 재계산하지 않음).
            dedLevelText = getDedLevelText(dedlevel),
            bill = getBillByLevel(dedlevel),
            reservedCommand = reserved,
        )
    }

    /**
     * PHP `calcLeadershipBonus($officerLevel, $nationLevel)` (func_process.php:52-61). OfficerLevelModule이
     * 동일 식을 (demotion 후) 쓰지만, GeneralList의 lbonus는 PHP가 **raw officer_level**을 그대로 넘긴다
     * (GeneralList.php:262 `calcLeadershipBonus($rawGeneral['officer_level'], ...)`). 데모션 미적용.
     */
    private fun calcLeadershipBonus(officerLevel: Int, nationLevel: Int): Int = when {
        officerLevel == 12 -> nationLevel * 2
        officerLevel >= 5 -> nationLevel
        else -> 0
    }

    /**
     * PHP env(GeneralList.php:152) — game_env getValues(['year','month','turntime','turnterm',
     * 'autorun_user','killturn']). opensamguk world_state에서 가능한 것만 채우고, KV 미배선 값은 null.
     */
    private fun buildEnv(): GeneralListEnv {
        val w = world.findAll().firstOrNull()
        val config = w?.config ?: emptyMap()
        return GeneralListEnv(
            year = w?.currentYear ?: 0,
            month = w?.currentMonth ?: 0,
            turnterm = (w?.tickSeconds ?: 0) / 60,
            turntime = config["turntime"]?.toString(),           // KV 미배선 시 null
            autorunUser = (config["autorun_user"] as? Number)?.toInt(), // KV 미배선 시 null
            killturn = (config["killturn"] as? Number)?.toInt(),  // KV 미배선 시 null
        )
    }
}
