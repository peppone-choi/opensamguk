package opensamguk.gameapi.dto

import opensamguk.logic.input.DispatchFailure
import opensamguk.logic.input.DispatchStatus
import opensamguk.logic.input.HwihaPhase

data class DispatchPendingResponse(val result: Boolean, val code: DispatchFailure? = null,
    val now: HwihaPhase? = null, val dispatches: List<DispatchPendingItem> = emptyList())
data class DispatchPendingItem(val dispatchId: String, val issuerId: Int, val targetId: Int, val countyId: Int,
    val issuedAt: HwihaPhase, val dueAt: HwihaPhase, val status: DispatchStatus,
    val currentFailure: DispatchFailure? = null)
