package opensamguk.logic.imperial

/** Applies an emperor's death to the world payload without changing a nation's ruler state. */
object ImperialDeathTransition {
    fun apply(
        worldMeta: Map<String, Any?>,
        deceasedGeneralId: Int,
        requestId: String,
        scriptedSuccessorGeneralId: Int?,
        candidates: Collection<ImperialCandidate>,
        year: Int,
        month: Int,
        reasonCode: String,
    ): Map<String, Any?> {
        require(deceasedGeneralId > 0)
        val state = ImperialWorldCodec.read(worldMeta) ?: return worldMeta
        state.transitions.singleOrNull { it.requestId == requestId }?.let { prior ->
            require(prior.fromHolderGeneralId == deceasedGeneralId && prior.year == year &&
                prior.month == month && prior.reasonCode == reasonCode &&
                prior.type in setOf(ImperialTransitionType.DEATH_SUCCESSION, ImperialTransitionType.VACANCY)) {
                "death event id belongs to a different imperial transition"
            }
            return worldMeta
        }
        val house = state.houses.singleOrNull { it.holderGeneralId == deceasedGeneralId } ?: return worldMeta
        val next = state.succeedAfterDeath(house.code, requestId, scriptedSuccessorGeneralId,
            candidates, year, month, reasonCode)
        return worldMeta + (ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(next))
    }
}
