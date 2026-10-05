package opensamguk.gameapi.frontier

import opensamguk.logic.input.Phase

/**
 * Pure decision. External contacts (logic ExternalContact) have no persisted store yet, so a nation member gets
 * NOT_SEEDED — never READY with an empty list, which would claim a verified "no contact" (inland) answer.
 */
object FrontierProjection {
    fun project(now: Phase, nationId: Int): FrontierDto {
        if (nationId <= 0) return unavailable(FrontierReason.NO_NATION, now)
        return FrontierDto(FrontierStatus.NOT_SEEDED, FrontierReason.CONTACTS_NOT_SEEDED, now, null)
    }

    fun unavailable(reason: FrontierReason, now: Phase? = null) = FrontierDto(FrontierStatus.UNAVAILABLE, reason, now, null)
}
