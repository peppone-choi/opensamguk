package opensamguk.gameapi.rank

import opensamguk.gameapi.dto.BestGeneral
import opensamguk.gameapi.dto.EmperorRecord
import opensamguk.gameapi.dto.GeneralRank
import opensamguk.gameapi.dto.HallRecord
import opensamguk.gameapi.dto.KingdomRank
import opensamguk.gameapi.dto.NpcGeneral
import opensamguk.gameapi.dto.TrafficSummary
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
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
                    nation = nationName(nidx, g.nationId),
                    nationColor = nationColor(nidx, g.nationId),
                    officerLevel = g.officerLevel,
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    experience = g.experience,
                    devotion = g.dedication,
                    crew = g.crew,
                    cityName = if (g.cityId != 0) cityIndex[g.cityId]?.name ?: "" else "",
                )
            }
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
