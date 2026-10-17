package opensamguk.logic.prisoner

object PrisonerTransfer {
    fun transfer(
        state: PrisonerState, requestId: String, fromGeneralId: Long, toGeneralId: Long,
        soldiers: Set<SoldierRef> = emptySet(), generalIds: Set<Long> = emptySet(),
        conditionsApproved: Boolean?,
    ): PrisonerResult {
        val receipt = PrisonerReceipt("transfer", listOf(fromGeneralId.toString(), toGeneralId.toString(), "soldiers") +
            soldiers.sorted().map { it.toString() } + listOf("generals") + generalIds.sorted().map { it.toString() })
        state.retry(requestId, receipt)?.let { return it }
        if (fromGeneralId <= 0 || toGeneralId <= 0 || fromGeneralId == toGeneralId || soldiers.isEmpty() && generalIds.isEmpty() || toGeneralId in generalIds) {
            return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        }
        if (soldiers.any { it !in state.soldiers } || generalIds.any { it !in state.generals }) return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (soldiers.any { state.soldiers.getValue(it).ownerGeneralId != fromGeneralId } ||
            generalIds.any { state.generals.getValue(it).ownerGeneralId != fromGeneralId }) return PrisonerResult(state, PrisonerCode.NOT_OWNER)
        if (conditionsApproved == null) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (!conditionsApproved || soldiers.any { state.soldiers.getValue(it).status != SoldierCaptivity.CAPTIVE } ||
            generalIds.any { state.generals.getValue(it).status != GeneralCaptivity.CAPTIVE }) return PrisonerResult(state, PrisonerCode.ACTION_NOT_ALLOWED)
        val changed = state.copy(
            soldiers = state.soldiers + soldiers.associateWith { state.soldiers.getValue(it).copy(ownerGeneralId = toGeneralId) },
            generals = state.generals + generalIds.associateWith { state.generals.getValue(it).copy(ownerGeneralId = toGeneralId) },
        )
        return PrisonerResult(changed.remember(requestId, receipt), PrisonerCode.APPLIED)
    }
}
