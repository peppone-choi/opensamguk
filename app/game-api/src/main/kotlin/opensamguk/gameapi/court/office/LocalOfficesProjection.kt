package opensamguk.gameapi.court.office

import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeTenure

/**
 * Pure decision over decoded tenures. It never derives EFFECTIVE/NOMINAL, missing evidence or actual counties:
 * those need an OfficeJurisdictionSnapshot that no server source builds yet, so open tenures close as UNAVAILABLE
 * instead of guessed rows. A nation with no open tenure in an existing store is a verified empty READY.
 */
object LocalOfficesProjection {
    fun project(now: Phase, nationId: Int, tenures: List<OfficeTenure>): LocalOfficesDto {
        require(nationId > 0) { "nation must be resolved before projection" }
        val open = tenures.filter { it.nationId == nationId && it.endedTurn == null }
        if (open.isNotEmpty()) return unavailable(LocalOfficesReason.JURISDICTION_SNAPSHOT_UNAVAILABLE, now)
        return LocalOfficesDto(LocalOfficesStatus.READY, null, now, emptyList(), null, null)
    }

    fun notSeeded(now: Phase) =
        LocalOfficesDto(LocalOfficesStatus.NOT_SEEDED, LocalOfficesReason.TENURES_NOT_SEEDED, now, null, null, null)

    fun unavailable(reason: LocalOfficesReason, now: Phase? = null) =
        LocalOfficesDto(LocalOfficesStatus.UNAVAILABLE, reason, now, null, null, null)
}
