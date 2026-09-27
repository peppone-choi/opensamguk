package opensamguk.logic.season

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.common.world.WorldId

/** One turn is one 旬. Turn zero is the first 旬 of January. */
enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

data class SeasonCalendar(
    val springStartMonth: Int,
    val summerStartMonth: Int,
    val autumnStartMonth: Int,
    val winterStartMonth: Int,
) {
    init {
        require(1 < springStartMonth && springStartMonth < summerStartMonth &&
            summerStartMonth < autumnStartMonth && autumnStartMonth < winterStartMonth && winterStartMonth <= 12)
    }

    fun monthAt(turn: Long): Int = (Math.floorMod(turn, 36L) / 3L + 1L).toInt()

    fun seasonAt(turn: Long): Season = seasonForMonth(monthAt(turn))

    fun seasonForMonth(month: Int): Season {
        require(month in 1..12) { "month must be 1..12" }
        return when {
            month < springStartMonth || month >= winterStartMonth -> Season.WINTER
            month < summerStartMonth -> Season.SPRING
            month < autumnStartMonth -> Season.SUMMER
            else -> Season.AUTUMN
        }
    }

    /** Missing seasonal evidence stays closed. */
    fun passageOpen(availability: Availability, season: Season, openSeasons: Set<Season>): Boolean =
        when (availability) {
            Availability.ALWAYS -> true
            Availability.CLOSED -> false
            Availability.SEASONAL -> season in openSeasons
        }
}

enum class Availability { ALWAYS, SEASONAL, CLOSED }
enum class Terrain { PLAIN, RIVER, MOUNTAIN, WETLAND }
enum class SeasonalEventKind { DROUGHT, FLOOD, PLAGUE, LOCUST, FREEZE, RAINY_PASSAGE }

data class CountySeasonState(
    val countyId: Int,
    val terrain: Terrain,
    val population: Int,
    val agriculture: Int,
) {
    init {
        require(countyId > 0)
        require(population >= 0 && agriculture >= 0)
    }
}

data class SeasonalEffect(
    val trust: Int = 0,
    val population: Int = 0,
    val agriculture: Int = 0,
    val displaced: Int = 0,
    val passageClosed: Boolean = false,
) {
    init { require(displaced >= 0) }
}

data class SeasonalEventRule(
    val kind: SeasonalEventKind,
    val seasons: Set<Season>,
    val terrains: Set<Terrain>,
    val minimumPopulation: Int,
    val minimumAgriculture: Int,
    val chancePermille: Int,
    val effect: SeasonalEffect,
) {
    init {
        require(seasons.isNotEmpty() && terrains.isNotEmpty())
        require(minimumPopulation >= 0 && minimumAgriculture >= 0)
        require(chancePermille in 0..1000)
    }
}

data class SeasonalOccurrence(val countyId: Int, val kind: SeasonalEventKind, val effect: SeasonalEffect)

/** Pure decision. A distinct seed per county/event avoids order-dependent random draws. */
object SeasonalEvents {
    fun decide(
        hiddenSeed: String,
        worldId: WorldId,
        year: Int,
        month: Int,
        phase: Int,
        calendar: SeasonCalendar,
        counties: Collection<CountySeasonState>,
        rules: Collection<SeasonalEventRule>,
    ): List<SeasonalOccurrence> {
        require(hiddenSeed.isNotBlank() && phase in 1..3)
        val season = calendar.seasonForMonth(month)
        require(counties.map { it.countyId }.distinct().size == counties.size) { "duplicate county" }
        require(rules.map { it.kind }.distinct().size == rules.size) { "duplicate event rule" }
        return counties.sortedBy { it.countyId }.flatMap { county ->
            rules.sortedBy { it.kind.name }.mapNotNull { rule ->
                if (season !in rule.seasons || county.terrain !in rule.terrains ||
                    county.population < rule.minimumPopulation || county.agriculture < rule.minimumAgriculture
                ) return@mapNotNull null
                val seed = serializeSeed(hiddenSeed, "seasonalEvent", worldId.value, year, month, phase,
                    county.countyId, rule.kind.name)
                val roll = RandUtil(LiteHashDrbg(seed)).nextRangeInt(0, 999)
                if (roll < rule.chancePermille) SeasonalOccurrence(county.countyId, rule.kind, rule.effect) else null
            }
        }
    }
}
