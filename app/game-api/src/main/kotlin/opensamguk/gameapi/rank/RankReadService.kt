package opensamguk.gameapi.rank

import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.BestGeneral
import opensamguk.gameapi.dto.EmperorDetail
import opensamguk.gameapi.dto.EmperorDetailCity
import opensamguk.gameapi.dto.EmperorDetailGeneral
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
import opensamguk.gameapi.dto.TrafficStat
import opensamguk.gameapi.dto.TrafficUser
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralListText
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.HallReadEntity
import opensamguk.gameapi.read.HallReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.StatisticReadEntity
import opensamguk.gameapi.read.StatisticReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.domain.metaInt
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.domestic.getExpLevel
import opensamguk.logic.util.jsonDecodeAny
import opensamguk.logic.world.SpecialityHelper
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * F3 — read-only ranking projections (spec `2026-06-02-F3-rankings-spec.md`).
 *
 * Pure projections of already-flushed `general`/`nation`/`city` rows via the existing read repos —
 * NO `ChangeRecorder`, NO `EntityManager` write, NO game-state mutation (game-api = read-only JPA).
 *
 * Sort-metric parity (spec §4): best-generals & npcs = `leadership+strength+intel` DESC; kingdoms =
 * `power` proxy (SUM crew) DESC; generals default = `experience` DESC (the page re-sorts client-side).
 * Every board breaks ties on `general.id`/`nation.id` ASC for a deterministic stable order (PHP `usort`
 * is stable on 8.0+; the id tiebreak preserves that without adding a non-stable comparator).
 * `rank` is the 1-based array index assigned AFTER the sort.
 *
 * Historical boards read only persisted rows: `hall`, `world_state`, and `statistic`-backed live
 * state. Missing source values stay empty/zero instead of being invented.
 */
@Service
class RankReadService(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val hall: HallReadRepository,
    private val worldStates: WorldStateReadRepository,
    private val statistics: StatisticReadRepository,
    private val gameKv: GameKvReadRepository? = null,
    private val accessLogs: GeneralAccessLogReadRepository? = null,
) {

    companion object {
        /** Top-N cap for the general boards (spec OQ-6). Pages render the full array; this bounds payload. */
        const val DEFAULT_LIMIT = 100

        const val NEUTRAL_NATION_NAME = "재야"
        const val NEUTRAL_NATION_COLOR = "#000000"

        /** `general.npc_state = 1` = 빙의(악령) gallery — the legacy `npc=1` set (spec OQ-7). */
        const val NPC_STATE_GALLERY = 1
    }

    private data class HallType(val label: String, val percent: Boolean)

    private data class UnifiedEmperorSnapshot(
        val record: EmperorRecord,
        val winner: NationReadEntity,
    )

    private val hallTypes = linkedMapOf(
        "experience" to HallType("명 성", false),
        "dedication" to HallType("계 급", false),
        "firenum" to HallType("계 략 성 공", false),
        "warnum" to HallType("전 투 횟 수", false),
        "killnum" to HallType("승 리", false),
        "winrate" to HallType("승 률", true),
        "occupied" to HallType("점 령", false),
        "killcrew" to HallType("사 살", false),
        "killrate" to HallType("살 상 률", true),
        "killcrew_person" to HallType("대 인 사 살", false),
        "killrate_person" to HallType("대 인 살 상 률", true),
        "dex1" to HallType("보 병 숙 련 도", false),
        "dex2" to HallType("궁 병 숙 련 도", false),
        "dex3" to HallType("기 병 숙 련 도", false),
        "dex4" to HallType("귀 병 숙 련 도", false),
        "dex5" to HallType("차 병 숙 련 도", false),
        "ttrate" to HallType("전 력 전 승 률", true),
        "tlrate" to HallType("통 솔 전 승 률", true),
        "tsrate" to HallType("일 기 토 승 률", true),
        "tirate" to HallType("설 전 승 률", true),
        "betgold" to HallType("베 팅 투 자 액", false),
        "betwin" to HallType("베 팅 당 첨", false),
        "betwingold" to HallType("베 팅 수 익 금", false),
        "betrate" to HallType("베 팅 수 익 률", true),
    )

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
                    politics = g.politics, // 정치/매력 (RTK14 divergence)
                    charm = g.charm, // 정치/매력 (RTK14 divergence)
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
                    politics = g.politics, // 정치/매력 (RTK14 divergence)
                    charm = g.charm, // 정치/매력 (RTK14 divergence)
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
                    politics = g.politics, // 정치/매력 (RTK14 divergence)
                    charm = g.charm, // 정치/매력 (RTK14 divergence)
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
     * 외교권자/조언자는 PHP `checkSecretPermission(general, false)`와 같은 shared
     * [SecretPermission]으로 계산한다. permission은 별도 컬럼이 아니라 general.meta에, penalty는
     * general.penalty에 보관되므로 둘을 모두 전달해야 한다.
     *
     * BLOCKED(원천 부재 → quarantine, 날조 금지): 성향(typeCode 한글명)은 nation type → 한글명 헬퍼가
     * 이 번들 disjoint 범위 밖이라 raw type_code를 노출한다.
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

                val secretPermissionByGeneral = gens.associateWith { g ->
                    SecretPermission.check(
                        nationId = g.nationId,
                        officerLevel = g.officerLevel,
                        meta = g.meta,
                        penalty = g.penalty,
                        checkSecretLimit = false,
                    )
                }

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
                    ambassadors = gens.filter { secretPermissionByGeneral.getValue(it) == 4 }.map { it.name },
                    auditorCount = gens.count { secretPermissionByGeneral.getValue(it) == 3 },
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

    fun hallOfFame(): List<HallRecord> =
        hall.findAllByOrderByTypeAscValueDescIdAsc()
            .groupBy { it.type }
            .flatMap { (type, rows) ->
                val hallType = hallTypes[type] ?: return@flatMap emptyList()
                rows.take(10).map { it.toHallRecord(hallType) }
            }

    private fun HallReadEntity.toHallRecord(hallType: HallType): HallRecord {
        return HallRecord(
            id = id,
            category = hallType.label,
            name = aux.stringValue("name").ifBlank { "-" },
            nation = aux.stringValue("nationName").ifBlank { "-" },
            nationColor = aux.stringValue("bgColor"),
            value = value,
            valueLabel = if (hallType.percent) "%.2f%%".format(Locale.US, value * 100.0) else "%,.0f".format(Locale.US, value),
            achievedAt = aux.stringValue("unitedTime"),
            turn = season,
        )
    }

    fun traffic(): TrafficSummary {
        val config = gameEnvironment()
        val recent = trafficHistory(config["recentTraffic"])
        val rows = runCatching { accessLogs?.findAll() }.getOrNull().orEmpty()
        val generalNames = generals.findAll().associate { it.id to it.name }
        val topRefreshers = rows
            .sortedWith(compareByDescending<opensamguk.gameapi.read.GeneralAccessLogReadEntity> { it.refresh }.thenBy { it.generalId })
            .mapNotNull { row ->
                val name = generalNames[row.generalId] ?: return@mapNotNull null
                TrafficUser(name, row.refresh, row.refreshScoreTotal)
            }
            .take(5)

        return TrafficSummary(
            refresh = config.intValue("refresh"),
            maxRefresh = config.intValue("maxrefresh"),
            currentOnline = config.intValue("online_user_cnt"),
            maxOnline = config.intValue("maxonline"),
            history = recent,
            totalRefresh = rows.sumOf { it.refresh },
            totalRefreshScore = rows.sumOf { it.refreshScoreTotal },
            topRefreshers = topRefreshers,
        )
    }

    private fun gameEnvironment(): Map<String, Any?> {
        val merged = LinkedHashMap(currentWorld()?.config.orEmpty())
        for (namespace in listOf("", "game_env", "global")) {
            val rows = runCatching { gameKv?.findByTableAndNamespace("game_env", namespace) }
                .getOrNull()
                .orEmpty()
            for (row in rows) {
                if (row.key !in merged) {
                    merged[row.key] = runCatching { jsonDecodeAny(row.value) }.getOrNull()
                }
            }
        }
        return merged
    }

    private fun trafficHistory(raw: Any?): List<TrafficStat> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            val row = item as? Map<*, *> ?: return@mapNotNull null
            TrafficStat(
                year = row.intValue("year"),
                month = row.intValue("month"),
                date = row.stringValue("date"),
                refresh = row.intValue("refresh"),
                online = row.intValue("online"),
            )
        }

    fun emperor(): List<EmperorRecord> =
        unifiedEmperorSnapshot()?.let { listOf(it.record) }.orEmpty()

    fun emperorDetail(id: Int): EmperorDetail? {
        val snapshot = unifiedEmperorSnapshot() ?: return null
        if (id != snapshot.record.id) return null

        val winnerGenerals = generals.findAll()
            .asSequence()
            .filter { it.nationId == snapshot.winner.id }
            .sortedBy { it.id }
            .map {
                EmperorDetailGeneral(
                    name = it.name,
                    leadership = it.leadership,
                    strength = it.strength,
                    intel = it.intel,
                )
            }
            .toList()
        val winnerCities = cities.findAll()
            .asSequence()
            .filter { it.nationId == snapshot.winner.id }
            .sortedBy { it.id }
            .toList()
        val record = snapshot.record

        return EmperorDetail(
            id = record.id,
            name = record.name,
            nation = record.nation,
            nationColor = record.nationColor,
            unifiedAt = record.unifiedAt,
            turn = record.turn,
            year = record.year,
            month = record.month,
            generalCount = record.generalCount,
            cityCount = record.cityCount,
            totalGold = snapshot.winner.gold,
            totalRice = snapshot.winner.rice,
            totalPop = winnerCities.sumOf { it.population.toLong() },
            generals = winnerGenerals,
            cities = winnerCities.map { EmperorDetailCity(name = it.name, level = it.level, pop = it.population) },
        )
    }

    private fun unifiedEmperorSnapshot(): UnifiedEmperorSnapshot? {
        val world = currentWorld() ?: return null
        if (world.isunited !in setOf(2, 3)) return null

        val activeNations = nations.findAll().filter { it.level > 0 }
        if (activeNations.size != 1) return null

        val winner = activeNations.single()
        val cityCount = cities.countByNationId(winner.id).toInt()
        if (cityCount == 0 || cityCount != cities.count().toInt()) return null

        val latestStatistic = statistics.findFirstByOrderByIdDesc()
        return UnifiedEmperorSnapshot(
            record = EmperorRecord(
                id = 1,
                name = winner.name,
                nation = winner.name,
                nationColor = winner.color,
                unifiedAt = world.updatedAt?.toString() ?: world.startTime?.toString() ?: "",
                turn = world.currentPhase,
                year = world.currentYear,
                month = world.currentMonth,
                generalCount = liveGeneralCount(winner.id, latestStatistic),
                cityCount = cityCount,
            ),
            winner = winner,
        )
    }

    private fun liveGeneralCount(nationId: Int, latestStatistic: StatisticReadEntity?): Int =
        generals.countByNationId(nationId).toInt().takeIf { it > 0 }
            ?: latestStatistic?.genCount?.substringBefore(" / ")?.toIntOrNull()
            ?: 0

    private fun currentWorld(): WorldStateReadEntity? =
        worldStates.findProcessWorld()

    private fun Map<*, *>.stringValue(key: String): String =
        when (val value = this[key]) {
            null -> ""
            is String -> value
            else -> value.toString()
        }

    private fun Map<*, *>.intValue(key: String): Int =
        when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
}
