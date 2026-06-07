package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.FrontNationInfo
import opensamguk.gameapi.dto.MyBossResponse
import opensamguk.gameapi.dto.MyCitiesResponse
import opensamguk.gameapi.dto.MyCitySummary
import opensamguk.gameapi.dto.MyGeneralSummary
import opensamguk.gameapi.dto.MyGeneralsResponse
import opensamguk.gameapi.dto.MyNationDetailResponse
import opensamguk.gameapi.dto.MyPageResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.common.constants.GameConst
import opensamguk.logic.domestic.getBill
import opensamguk.logic.domestic.getDedLevel
import opensamguk.logic.domestic.getDedLevelText
import opensamguk.logic.domain.metaInt
import opensamguk.logic.world.SpecialityHelper
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 1 — the `my-*` read endpoints web/game's `lib/api.ts` calls (absent today). Each resolves the
 * caller's owned general via [GeneralResolver] (verified JWT principal → `general_owner` → game-state
 * read), then assembles from the existing read repositories. ALL reads — never a game-state write.
 *
 * No-character contract: `/api/my-page` 404s (no general to show); the nation/list endpoints return a
 * `result:false` empty shape (so the UI can render 장수선택/빙의 without treating it as an error).
 */
@RestController
@RequestMapping("/api")
class MyController(
    private val resolver: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val nations: NationReadRepository,
) {

    @GetMapping("/my-page")
    fun myPage(@AuthenticationPrincipal userId: Long?): ResponseEntity<MyPageResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId) ?: return ResponseEntity.notFound().build()
        val g = resolved.general
        val nationName = if (g.nationId != 0) nations.findById(g.nationId).map { it.name }.orElse(null) else null
        val cityName = if (g.cityId != 0) cities.findById(g.cityId).map { it.name }.orElse(null) else null
        return ResponseEntity.ok(
            MyPageResponse(
                generalId = g.id,
                name = g.name,
                nationId = g.nationId,
                nationName = nationName,
                cityId = g.cityId,
                cityName = cityName,
                officerLevel = resolved.officerLevel,
                permission = resolved.permission,
                leadership = g.leadership,
                strength = g.strength,
                intel = g.intel,
                injury = g.injury,
                experience = g.experience,
                dedication = g.dedication,
                gold = g.gold,
                rice = g.rice,
                crew = g.crew,
                train = g.train,
                atmos = g.atmos,
                picture = g.picture,
                imageServer = g.imageServer,
            ),
        )
    }

    @GetMapping("/my-generals")
    fun myGenerals(@AuthenticationPrincipal userId: Long?): ResponseEntity<MyGeneralsResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.ok(MyGeneralsResponse(result = false, nationId = 0, generals = emptyList()))
        val nationId = resolved.nationId
        val meId = resolved.general.id
        // 국가 레벨(officerLevelText/lbonus 계산 입력). 재야/국가 부재 시 0(PHP getOfficerLevelText의 nlevel).
        val nationLevel = if (nationId != 0) nations.findById(nationId).map { it.level }.orElse(0) else 0
        // PHP b_myGenInfo 기본 정렬(type=1)=officer_level DESC. 기존 리포지토리 메서드가 동형.
        val rows = if (nationId != 0) {
            generals.findByNationIdOrderByOfficerLevelDescIdAsc(nationId)
        } else {
            listOf(resolved.general) // 재야: only self
        }
        val summaries = rows.map { g ->
            MyGeneralSummary(
                generalId = g.id,
                name = g.name,
                cityId = g.cityId,
                officerLevel = g.officerLevel,
                leadership = g.leadership,
                strength = g.strength,
                intel = g.intel,
                crew = g.crew,
                npcState = g.npcState,
                mine = g.id == meId,
                // ── b_myGenInfo 15컬럼 보강(C3①). raw 코드 → 한글은 이식된 헬퍼만 재사용(날조 아님). ──
                picture = g.picture,
                imageServer = g.imageServer,
                officerLevelText = F4StateText.officerLevelText(g.officerLevel, nationLevel),
                // 계급/봉록 — PHP b_myGenInfo는 getDed($general['dedication'])/getBill($general['dedication'])로
                // dedication에서 직접 산출한다(STORED dedlevel 컬럼을 거치지 않음). byte-parity 위해 동일하게 직접 산출.
                dedLevelText = getDedLevelText(getDedLevel(g.dedication.toDouble())),
                honorText = F4StateText.honorText(g.experience),
                bill = getBill(g.dedication.toDouble()),
                gold = g.gold,
                rice = g.rice,
                personalText = GameConst.personalityNameOf(g.personalCode),
                specialDomesticText = if (g.specialCode == "None") "-" else SpecialityHelper.domesticName(g.specialCode),
                specialWarText = if (g.special2Code == "None") "-" else SpecialityHelper.warName(g.special2Code),
                belong = metaInt(g.meta, "belong"),
                injury = g.injury,
                lbonus = calcLeadershipBonus(g.officerLevel, nationLevel),
            )
        }
        return ResponseEntity.ok(MyGeneralsResponse(result = true, nationId = nationId, generals = summaries))
    }

    @GetMapping("/my-cities")
    fun myCities(@AuthenticationPrincipal userId: Long?): ResponseEntity<MyCitiesResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.ok(MyCitiesResponse(result = false, nationId = 0, cities = emptyList()))
        val nationId = resolved.nationId
        if (nationId == 0) {
            return ResponseEntity.ok(MyCitiesResponse(result = true, nationId = 0, cities = emptyList()))
        }
        val rows = cities.findByNationIdOrderByIdAsc(nationId).map { c ->
            MyCitySummary(
                cityId = c.id,
                name = c.name,
                level = c.level,
                region = c.region,
                population = c.population,
                populationMax = c.populationMax,
                defense = c.defense,
                wall = c.wall,
            )
        }
        return ResponseEntity.ok(MyCitiesResponse(result = true, nationId = nationId, cities = rows))
    }

    @GetMapping("/my-boss")
    fun myBoss(@AuthenticationPrincipal userId: Long?): ResponseEntity<MyBossResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.ok(MyBossResponse(result = false, nationId = 0, hasBoss = false, bossGeneralId = null, bossName = null, bossOfficerLevel = null))
        val nationId = resolved.nationId
        if (nationId == 0) {
            return ResponseEntity.ok(MyBossResponse(result = true, nationId = 0, hasBoss = false, bossGeneralId = null, bossName = null, bossOfficerLevel = null))
        }
        val boss = generals.findFirstByNationIdOrderByOfficerLevelDesc(nationId)
        return ResponseEntity.ok(
            MyBossResponse(
                result = true,
                nationId = nationId,
                hasBoss = boss != null,
                bossGeneralId = boss?.id,
                bossName = boss?.name,
                bossOfficerLevel = boss?.officerLevel,
            ),
        )
    }

    @GetMapping("/my-nation-detail")
    fun myNationDetail(@AuthenticationPrincipal userId: Long?): ResponseEntity<MyNationDetailResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.ok(MyNationDetailResponse(result = false, hasNation = false, nation = null, cityCount = 0, generalCount = 0))
        val nationId = resolved.nationId
        if (nationId == 0) {
            return ResponseEntity.ok(MyNationDetailResponse(result = true, hasNation = false, nation = null, cityCount = 0, generalCount = 0))
        }
        val nation = nations.findById(nationId).orElse(null)
            ?: return ResponseEntity.ok(MyNationDetailResponse(result = true, hasNation = false, nation = null, cityCount = 0, generalCount = 0))
        return ResponseEntity.ok(
            MyNationDetailResponse(
                result = true,
                hasNation = true,
                nation = FrontNationInfo(
                    id = nation.id,
                    name = nation.name,
                    color = nation.color,
                    level = nation.level,
                    gold = nation.gold,
                    rice = nation.rice,
                    tech = nation.tech,
                    capitalCityId = nation.capitalCityId,
                ),
                cityCount = cities.countByNationId(nationId).toInt(),
                generalCount = generals.countByNationId(nationId).toInt(),
            ),
        )
    }

    /**
     * PHP `calcLeadershipBonus($officerLevel, $nationLevel)` (func_process.php:52-61) 충실 이식.
     * b_myGenInfo는 raw officer_level을 그대로 넘긴다(데모션 미적용). 통솔 컬럼에 "+{lbonus}"(cyan)로 부가.
     * GeneralListController/GeneralsController의 동명 헬퍼와 동식.
     */
    private fun calcLeadershipBonus(officerLevel: Int, nationLevel: Int): Int = when {
        officerLevel == 12 -> nationLevel * 2
        officerLevel >= 5 -> nationLevel
        else -> 0
    }
}
