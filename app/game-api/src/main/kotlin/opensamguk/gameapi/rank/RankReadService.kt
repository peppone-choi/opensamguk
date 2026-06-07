package opensamguk.gameapi.rank

import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.BestGeneral
import opensamguk.gameapi.dto.EmperorRecord
import opensamguk.gameapi.dto.GeneralRank
import opensamguk.gameapi.dto.HallRecord
import opensamguk.gameapi.dto.KingdomRank
import opensamguk.gameapi.dto.KingdomRoster
import opensamguk.gameapi.dto.KingdomRosterChief
import opensamguk.gameapi.dto.KingdomRosterCity
import opensamguk.gameapi.dto.KingdomRosterGeneral
import opensamguk.gameapi.dto.KingdomRosterNation
import opensamguk.gameapi.dto.KingdomRosterNeutral
import opensamguk.gameapi.dto.NpcGeneral
import opensamguk.gameapi.dto.TrafficSummary
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralListText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domestic.getExpLevel
import opensamguk.logic.world.SpecialityHelper
import org.springframework.stereotype.Service

/**
 * F3 — read-only ranking projections (spec `2026-06-02-F3-rankings-spec.md`).
 *
 * Pure projections of already-flushed `general`/`nation`/`city` rows via the existing read repos —
 * NO `ChangeRecorder`, NO `EntityManager` write, NO game-state mutation (game-api = read-only JPA).
 *
 * Sort-metric parity (spec §4): best-generals & npcs = `leadership+strength+intel` DESC; kingdoms =
 * `power` proxy (SUM crew) DESC; generals default = `experience` DESC (the page re-sorts client-side).
 * Every board breaks ties on `general.id`/`nation.id` ASC for a deterministic stable order (PHP `usort`
 * is stable on 8.0+; the id tiebreak preserves that without adding a non-stable comparator — CLAUDE.md
 * rule 6). `rank` is the 1-based array index assigned AFTER the sort.
 *
 * Empty/zero defaults (NOT fabricated): hall-of-fame (`hall` empty in 1010, OQ-5), traffic (no
 * access-log infra, OQ-2), emperor (no unification-history table, OQ-1).
 */
@Service
class RankReadService(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
) {

    companion object {
        /** Top-N cap for the general boards (spec OQ-6). Pages render the full array; this bounds payload. */
        const val DEFAULT_LIMIT = 100

        const val NEUTRAL_NATION_NAME = "재야"
        const val NEUTRAL_NATION_COLOR = "#000000"

        /** `general.npc_state = 1` = 빙의(악령) gallery — the legacy `npc=1` set (spec OQ-7). */
        const val NPC_STATE_GALLERY = 1
    }

    /** Name/color lookup for the nation join (id 0 / unknown id → 재야 / #000000). */
    private fun nationIndex(): Map<Int, NationReadEntity> =
        nations.findAll().associateBy { it.id }

    private fun nationName(index: Map<Int, NationReadEntity>, nationId: Int): String =
        if (nationId == 0) NEUTRAL_NATION_NAME else index[nationId]?.name ?: NEUTRAL_NATION_NAME

    private fun nationColor(index: Map<Int, NationReadEntity>, nationId: Int): String =
        if (nationId == 0) NEUTRAL_NATION_COLOR else index[nationId]?.color ?: NEUTRAL_NATION_COLOR

    private fun GeneralReadEntity.total(): Int = leadership + strength + intel

    /**
     * 특기 표시 이름 보정(PossessionController.specialName과 동일 규칙). SpecialityHelper는 미등록 코드를
     * 그대로 반환하므로, "None"/공백/미해석은 '-'로 보정(PHP None.php `$name`='-').
     */
    private fun specialName(resolved: String, code: String): String =
        if (code.isBlank() || code == "None") "-" else resolved

    /**
     * 레벨(Lv) 산출 — GeneralList과 동일 규칙. STORED `explevel`(meta) 우선, 없으면 getExpLevel(experience)
     * 재계산(엔진이 쓰는 동일 공식 → 패러티 손실 없음). PHP a_npcList는 `general.explevel` 컬럼을 그대로
     * 출력하나 opensamguk엔 컬럼이 없어 meta-first/recompute로 동치 처리한다.
     */
    private fun GeneralReadEntity.expLevel(): Int =
        if (meta.containsKey("explevel")) metaInt(meta, "explevel") else getExpLevel(experience.toDouble())

    // ── 2.1 best-generals: total-aptitude DESC, tie id ASC, incl NPC ───────────────────────────────
    fun bestGenerals(limit: Int = DEFAULT_LIMIT): List<BestGeneral> {
        val nidx = nationIndex()
        return generals.findAll()
            .sortedWith(compareByDescending<GeneralReadEntity> { it.total() }.thenBy { it.id })
            .take(limit)
            .mapIndexed { i, g ->
                BestGeneral(
                    rank = i + 1,
                    generalId = g.id,
                    name = g.name,
                    nation = nationName(nidx, g.nationId),
                    nationColor = nationColor(nidx, g.nationId),
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    total = g.total(),
                )
            }
    }

    // ── 2.2 generals: default experience DESC, tie id ASC, incl NPC ────────────────────────────────
    fun generals(limit: Int = DEFAULT_LIMIT): List<GeneralRank> {
        val nidx = nationIndex()
        return generals.findAll()
            .sortedWith(compareByDescending<GeneralReadEntity> { it.experience }.thenBy { it.id })
            .take(limit)
            .mapIndexed { i, g ->
                GeneralRank(
                    rank = i + 1,
                    generalId = g.id,
                    name = g.name,
                    nation = nationName(nidx, g.nationId),
                    nationColor = nationColor(nidx, g.nationId),
                    officerLevel = g.officerLevel,
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    experience = g.experience,
                    devotion = g.dedication,
                    crew = g.crew,
                )
            }
    }

    // ── 2.3 kingdoms: power proxy (SUM crew) DESC, tie id ASC, exclude nationId 0 ───────────────────
    fun kingdoms(): List<KingdomRank> {
        val cityIndex = cities.findAll().associateBy { it.id }
        return nations.findAll()
            .filter { it.id != 0 }
            .map { n ->
                val power = generals.sumCrewByNationId(n.id)
                val pop = cities.sumPopulationByNationId(n.id)
                val genNum = generals.countByNationId(n.id).toInt()
                val cityCount = cities.countByNationId(n.id).toInt()
                val capitalName = n.capitalCityId?.let { cityIndex[it]?.name } ?: ""
                KingdomDraft(n, power, pop, genNum, cityCount, capitalName)
            }
            .sortedWith(compareByDescending<KingdomDraft> { it.power }.thenBy { it.nation.id })
            .mapIndexed { i, d ->
                KingdomRank(
                    rank = i + 1,
                    nationId = d.nation.id,
                    name = d.nation.name,
                    color = d.nation.color,
                    level = d.nation.level,
                    levelText = GameConst.nationLevelNameOf(d.nation.level),
                    gold = d.nation.gold,
                    rice = d.nation.rice,
                    pop = d.pop,
                    genNum = d.genNum,
                    power = d.power,
                    cityCount = d.cityCount,
                    capitalName = d.capitalName,
                )
            }
    }

    private data class KingdomDraft(
        val nation: NationReadEntity,
        val power: Long,
        val pop: Long,
        val genNum: Int,
        val cityCount: Int,
        val capitalName: String,
    )

    // ── 2.4 npcs: npc_state=1, total-aptitude DESC, tie id ASC, no rank ─────────────────────────────
    fun npcs(): List<NpcGeneral> {
        val nidx = nationIndex()
        val cityIndex: Map<Int, CityReadEntity> = cities.findAll().associateBy { it.id }
        return generals.findByNpcStateOrderByIdAsc(NPC_STATE_GALLERY)
            .sortedWith(compareByDescending<GeneralReadEntity> { it.total() }.thenBy { it.id })
            .map { g ->
                NpcGeneral(
                    generalId = g.id,
                    name = g.name,
                    npc = g.npcState,
                    nation = nationName(nidx, g.nationId),
                    nationColor = nationColor(nidx, g.nationId),
                    officerLevel = g.officerLevel,
                    // 악령 이름 = owner_name. BLOCKED(컬럼 부재) → null(FE "-"). 날조 금지.
                    ownerName = null,
                    explevel = g.expLevel(),
                    personalText = GameConst.personalityNameOf(g.personalCode),
                    specialDomesticName = specialName(SpecialityHelper.domesticName(g.specialCode), g.specialCode),
                    specialWarName = specialName(SpecialityHelper.warName(g.special2Code), g.special2Code),
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    total = g.total(),
                    experience = g.experience,
                    devotion = g.dedication,
                    crew = g.crew,
                    cityName = if (g.cityId != 0) cityIndex[g.cityId]?.name ?: "" else "",
                )
            }
    }

    // ── 2.4b kingdom-roster: 세력일람(a_kingdomList.php) ROSTER — leaderboard와 별개 화면 ──────────────
    /**
     * 세력일람 ROSTER. PHP `a_kingdomList.php` 충실 이식:
     *  - 국가는 `power` DESC(PHP `uasort power <=>`)로 정렬, nation 0(재야)은 항상 마지막 별도 섹션.
     *  - 각 국가의 장수는 `ORDER BY dedication DESC`로 1회 조회 후 nation별 버킷팅(삽입순서 = dedication DESC).
     *  - 수뇌 직책표: officer_level >= 5 장수를 `chiefs[officerLevel] = general`로 덮어쓰기(dedication DESC
     *    순회이므로 동일 직책에 둘 이상이면 마지막=최저 dedication이 자리, PHP와 동치). 직책 행은 12→5 고정 8칸.
     *  - 속령 일람: level>0이면 도시 목록(수도=capitalCityId로 FE가 cyan 강조), level==0이면 PHP는
     *    "현재 위치 = chiefs[12] 도시"를 보이지만 opensamguk은 cities 목록+capitalCityId만 노출하고 FE가 분기.
     *
     * BLOCKED(원천 부재 → quarantine, 날조 금지):
     *  - 외교권자(ambassadors)/조언자(auditorCount): `checkSecretPermission`==4/3 판정에 필요한
     *    `general.permission` 컬럼이 opensamguk 스키마에 없다 → 빈 배열/0 고정.
     *  - 성향(typeCode 한글명): nation type → 한글명 헬퍼 미이식(이 번들 disjoint 범위 밖) → raw type_code 노출.
     */
    fun kingdomRoster(): KingdomRoster {
        val allGenerals = generals.findAll().sortedByDescending { it.dedication } // PHP ORDER BY dedication DESC
        val allCities = cities.findAll().sortedBy { it.id }                       // 속령 일람 id ASC 안정 출력
        val generalsByNation: Map<Int, List<GeneralReadEntity>> = allGenerals.groupBy { it.nationId }
        val citiesByNation: Map<Int, List<CityReadEntity>> = allCities.groupBy { it.nationId }

        // 국력 DESC 정렬(PHP uasort power <=>); 동률 id ASC(결정적 출력 tie-break — §6 패러티 순서 불변).
        val rosterNations = nations.findAll()
            .filter { it.id != 0 }
            .sortedWith(compareByDescending<NationReadEntity> { it.power }.thenBy { it.id })
            .map { n ->
                val gens = generalsByNation[n.id].orEmpty()
                val cityList = citiesByNation[n.id].orEmpty()

                // 수뇌 직책 버킷: officer_level >= 5만, dedication DESC 순회 중 overwrite(PHP 동치).
                val chiefByLevel = HashMap<Int, GeneralReadEntity>()
                gens.forEach { g -> if (g.officerLevel >= 5) chiefByLevel[g.officerLevel] = g }

                // 12→5 고정 8칸 직책표(공석은 name="-"/npc=0).
                val chiefs = (12 downTo 5).map { lv ->
                    val c = chiefByLevel[lv]
                    KingdomRosterChief(
                        officerLevelText = GeneralListText.officerLevelText(lv, n.level),
                        name = c?.name ?: "-",
                        npc = c?.npcState ?: 0,
                    )
                }

                KingdomRosterNation(
                    nationId = n.id,
                    name = n.name,
                    color = n.color,
                    typeCode = n.typeCode, // BLOCKED 성향 한글명 → raw code
                    level = n.level,
                    levelText = GameConst.nationLevelNameOf(n.level),
                    power = n.power,
                    genNum = gens.size,
                    cityCount = cityList.size,
                    chiefs = chiefs,
                    ambassadors = emptyList(), // BLOCKED — permission 컬럼 부재
                    auditorCount = 0,           // BLOCKED — permission 컬럼 부재
                    cities = cityList.map { KingdomRosterCity(it.id, it.name) },
                    capitalCityId = n.capitalCityId,
                    generals = gens.map { KingdomRosterGeneral(it.name, it.npcState) },
                )
            }

        // 재야(nation 0) 섹션.
        val neutralGens = generalsByNation[0].orEmpty()
        val neutralCities = citiesByNation[0].orEmpty()
        val neutral = KingdomRosterNeutral(
            genNum = neutralGens.size,
            cityCount = neutralCities.size,
            cities = neutralCities.map { KingdomRosterCity(it.id, it.name) },
            generals = neutralGens.map { KingdomRosterGeneral(it.name, it.npcState) },
        )

        return KingdomRoster(nations = rosterNations, neutral = neutral)
    }

    // ── 2.5 hall-of-fame: F3 default empty (OQ-5) ──────────────────────────────────────────────────
    fun hallOfFame(): List<HallRecord> = emptyList()

    // ── 2.6 traffic: F3 zero-fill (OQ-2) ───────────────────────────────────────────────────────────
    fun traffic(): TrafficSummary = TrafficSummary(
        todayUnique = 0,
        todayViews = 0,
        weekUnique = 0,
        weekViews = 0,
        monthUnique = 0,
        monthViews = 0,
        peakConcurrent = 0,
        currentOnline = 0,
        history = emptyList(),
    )

    // ── 2.7 emperor: F3 default empty (OQ-1) ───────────────────────────────────────────────────────
    fun emperor(): List<EmperorRecord> = emptyList()
}
