package opensamguk.gameapi.season

import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.input.Phase
import opensamguk.logic.season.SeasonCalendar
import opensamguk.logic.season.SeasonalCatalog
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Confirmed calendar values (`data/curated/han/world-event-values.json`); null when the build did not ship them. */
fun interface WorldSeasonCalendarSource {
    fun payload(): String?
}

@Component
class ClasspathWorldSeasonCalendarSource : WorldSeasonCalendarSource {
    private val cached: String? by lazy {
        javaClass.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun payload(): String? = cached

    companion object {
        const val RESOURCE = "/season/world-event-values.json"
    }
}

data class WorldSeasonSnapshot(
    val status: WorldSeasonStatus,
    val reason: String?,
    val now: Phase?,
    val calendar: SeasonCalendar?,
) {
    init {
        require((status == WorldSeasonStatus.READY) == (now != null && calendar != null && reason == null))
    }
}

/** Reads the process world date and the calendar only; no passage or event producer is inferred. */
@Component
class WorldSeasonReader(
    private val worlds: WorldStateReadRepository,
    private val calendars: WorldSeasonCalendarSource,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(worldId: Int): WorldSeasonSnapshot {
        val world = worlds.findProcessWorld() ?: return unavailable(WorldSeasonReason.WORLD_UNAVAILABLE, null)
        if (world.id <= 0 || worldId != world.id) return unavailable(WorldSeasonReason.WORLD_UNAVAILABLE, null)
        val now = try {
            require(world.currentYear > 0)
            Phase(world.currentYear, world.currentMonth, world.currentPhase)
        } catch (_: IllegalArgumentException) {
            return unavailable(WorldSeasonReason.WORLD_DATE_INVALID, null)
        }
        val payload = calendars.payload()
            ?: return WorldSeasonSnapshot(WorldSeasonStatus.NOT_SEEDED, WorldSeasonReason.SEASON_CALENDAR_ABSENT, now, null)
        val calendar = try {
            SeasonalCatalog.parseCalendar(payload)
        } catch (_: IllegalArgumentException) {
            return unavailable(WorldSeasonReason.SEASON_CALENDAR_INVALID, now)
        } catch (_: NoSuchElementException) {
            return unavailable(WorldSeasonReason.SEASON_CALENDAR_INVALID, now)
        }
        return WorldSeasonSnapshot(WorldSeasonStatus.READY, null, now, calendar)
    }

    private fun unavailable(reason: String, now: Phase?) =
        WorldSeasonSnapshot(WorldSeasonStatus.UNAVAILABLE, reason, now, null)
}
