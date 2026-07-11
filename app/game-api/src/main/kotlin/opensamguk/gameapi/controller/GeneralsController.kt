package opensamguk.gameapi.controller

import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.PublicGeneral
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.getBillByLevel
import opensamguk.logic.domestic.getDedLevel
import opensamguk.logic.domestic.getDedLevelText
import opensamguk.logic.domestic.getExpLevel
import opensamguk.logic.world.SpecialityHelper
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/generals` (spec page 14 + 9-P0).
 *
 * PUBLIC, no auth: `GameApiSecurityConfig` ends in `.anyRequest().permitAll()`, so this path needs no
 * principal (the all-general 전체 장수 page must render for an anonymous visitor). Returns the
 * permission=0 public field surface — id/name/nation(id·name·color)/npc/officerLevel(+Text)/L·S·I/
 * 명성·계급 **레벨 버킷**(explevel·honorText·dedlevel·dedLevelText·bill)/crew/cityName.
 * NO raw exp/ded, NO gold/rice (미인증 공개 surface — OQ-5). 명성/계급은 레거시가
 * 공개 목록에서도 보여주는 버킷이므로 원값 대신 버킷만 노출한다(파생값 = 날조 아님). 인증된
 * permission-tiered P1/P2 view는 `/api/nation/general-list`·`/api/my-generals` 뒤에 둔다.
 *
 * Pure projection of live `general` rows joined to `nation` (name/color/level) and `city` (name).
 * nationId 0 → 재야 / #000000 (mirrors RankReadService neutral join). Read-only; never a game-state write.
 */
@RestController
@RequestMapping("/api/generals")
class GeneralsController(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val accessLogs: GeneralAccessLogReadRepository? = null,
) {

    @GetMapping
    fun all(): ResponseEntity<List<PublicGeneral>> {
        // 국가 id → (이름, 색, 레벨). 레벨은 officerLevelText 계산 입력(재야면 0).
        val nationInfo = nations.findAll().associate { it.id to NationInfo(it.name, it.color, it.level) }
        val cityName = cities.findAll().associate { it.id to it.name }
        val allGenerals = generals.findAll().sortedBy { it.id }
        val accessByGeneral = accessLogs
            ?.findByGeneralIdIn(allGenerals.map { it.id })
            ?.associateBy { it.generalId }
            .orEmpty()
        val rows = allGenerals
            .map { g ->
                val info = nationInfo[g.nationId]
                val isNeutral = g.nationId == 0
                val nationLevel = info?.level ?: 0
                val meta = g.meta
                // explevel/dedlevel은 엔진이 checkStatChange로 meta에 기록하는 STORED 값(GeneralListController와
                // 동일). meta에 없으면 raw stat에서 동일 공식으로 재산출(getExpLevel/getDedLevel — 패러티 손실 없음).
                val explevel =
                    if (meta.containsKey("explevel")) metaInt(meta, "explevel") else getExpLevel(g.experience.toDouble())
                val dedlevel =
                    if (meta.containsKey("dedlevel")) metaInt(meta, "dedlevel") else getDedLevel(g.dedication.toDouble())
                PublicGeneral(
                    generalId = g.id,
                    name = g.name,
                    nationId = g.nationId,
                    nationName = if (isNeutral) F4StateText.NEUTRAL_NATION_NAME else (info?.name ?: F4StateText.NEUTRAL_NATION_NAME),
                    nationColor = if (isNeutral) F4StateText.NEUTRAL_NATION_COLOR else (info?.color ?: F4StateText.NEUTRAL_NATION_COLOR),
                    npc = g.npcState,
                    officerLevel = g.officerLevel,
                    officerLevelText = F4StateText.officerLevelText(g.officerLevel, nationLevel),
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    politics = g.politics, // 정치/매력 (RTK14 divergence)
                    charm = g.charm, // 정치/매력 (RTK14 divergence)
                    // 명성 = explevel 버킷 + getHonor(experience). 계급 = dedlevel 버킷 + getDedLevelText/getBillByLevel.
                    explevel = explevel,
                    honorText = F4StateText.honorText(g.experience),
                    dedlevel = dedlevel,
                    dedLevelText = getDedLevelText(dedlevel),
                    bill = getBillByLevel(dedlevel),
                    cityName = if (g.cityId == 0) "" else (cityName[g.cityId] ?: ""),
                    // ── a_genList 15컬럼 보강(C3①). raw 코드 → 한글 해석은 이미 이식된 헬퍼만 재사용(날조 아님). ──
                    picture = g.picture,
                    imageServer = g.imageServer,
                    age = g.age,
                    // 성격/특기 한글명 — PHP displayCharInfo/displaySpecial*Info의 getName()과 동일 헬퍼.
                    // None → personalityNameOf="-", SpecialityHelper.domesticName/warName도 "None"이면 buildClass
                    // 의 getName()="-"이 정답이므로 명시적으로 "-"로 정규화한다(PHP None.php $name='-').
                    personalText = GameConst.personalityNameOf(g.personalCode),
                    specialDomesticText = if (g.specialCode == "None") "-" else SpecialityHelper.domesticName(g.specialCode),
                    specialWarText = if (g.special2Code == "None") "-" else SpecialityHelper.warName(g.special2Code),
                    injury = g.injury,
                    // PHP calcLeadershipBonus($officer_level, $nationLevel) — raw officer_level 사용(데모션 미적용).
                    lbonus = calcLeadershipBonus(g.officerLevel, nationLevel),
                    // 삭턴 — meta.killturn(스칼라 컬럼 부재). 미기재 시 null(날조 금지).
                    killturn = (g.meta["killturn"] as? Number)?.toInt(),
                    refreshScoreTotal = accessByGeneral[g.id]?.refreshScoreTotal ?: 0,
                )
            }
        return ResponseEntity.ok(rows)
    }

    /**
     * PHP `calcLeadershipBonus($officerLevel, $nationLevel)` (func_process.php:52-61) 충실 이식.
     * a_genList(GeneralList.php와 동일)은 raw officer_level을 그대로 넘긴다(데모션 미적용).
     * 통솔 컬럼에 "+{lbonus}"(cyan)로 부가 표시. GeneralListController.calcLeadershipBonus와 동식.
     */
    private fun calcLeadershipBonus(officerLevel: Int, nationLevel: Int): Int = when {
        officerLevel == 12 -> nationLevel * 2
        officerLevel >= 5 -> nationLevel
        else -> 0
    }

    /** 국가 조회 결과(이름/색/레벨)를 묶는 내부 보조 타입. */
    private data class NationInfo(val name: String, val color: String, val level: Int)
}
