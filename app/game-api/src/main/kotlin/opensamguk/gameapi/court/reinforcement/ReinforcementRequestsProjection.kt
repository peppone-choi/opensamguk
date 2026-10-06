package opensamguk.gameapi.court.reinforcement

object ReinforcementRequestsReason {
    const val WORLD_UNAVAILABLE = "WORLD_UNAVAILABLE"
    const val WORLD_DATE_INVALID = "WORLD_DATE_INVALID"
    const val REQUEST_SOURCE_ABSENT = "REQUEST_SOURCE_ABSENT"
}

/** Pure mapping. A list only exists for READY; NOT_SEEDED and UNAVAILABLE are never an empty "no requests". */
object ReinforcementRequestsProjection {
    fun project(snapshot: ReinforcementRequestsSnapshot): ReinforcementRequestsDto {
        require(snapshot.status != ReinforcementRequestsStatus.READY) { "READY needs a request source" }
        return ReinforcementRequestsDto(snapshot.status, snapshot.reason, snapshot.now, requests = null)
    }
}
