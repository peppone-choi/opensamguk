package opensamguk.gameapi.season

import opensamguk.logic.input.Phase

object WorldSeasonReason {
    const val WORLD_UNAVAILABLE = "WORLD_UNAVAILABLE"
    const val WORLD_DATE_INVALID = "WORLD_DATE_INVALID"
    const val SEASON_CALENDAR_ABSENT = "SEASON_CALENDAR_ABSENT"
    const val SEASON_CALENDAR_INVALID = "SEASON_CALENDAR_INVALID"
}

/**
 * Pure mapping. Passage stays UNAVAILABLE: no producer opens seasonal edges (`LandPassageState.seasonOpen` is never
 * written). Events stay NOT_SEEDED: `SeasonalEvents.decide` has no runtime caller, so nothing is replayed or rolled here.
 */
object WorldSeasonProjection {
    fun project(snapshot: WorldSeasonSnapshot): WorldSeasonDto {
        val now = snapshot.now
        val calendar = snapshot.calendar
        val ready = snapshot.status == WorldSeasonStatus.READY
        return WorldSeasonDto(
            status = snapshot.status,
            reason = snapshot.reason,
            now = now,
            season = if (ready && now != null && calendar != null) calendar.seasonForMonth(now.month) else null,
            phaseOfYear = if (ready && now != null && calendar != null) phaseOfYear(now) else null,
            passageStatus = WorldSeasonPassageStatus.UNAVAILABLE,
            closedEdges = null,
            eventsStatus = if (snapshot.status == WorldSeasonStatus.UNAVAILABLE) WorldSeasonStatus.UNAVAILABLE
                else WorldSeasonStatus.NOT_SEEDED,
            events = null,
        )
    }

    /** 0..35 — `(month-1)*3 + (phase-1)`. */
    fun phaseOfYear(now: Phase): Int = (now.month - 1) * 3 + now.phase
}
