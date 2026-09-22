package opensamguk.gameapi.dto

import opensamguk.logic.input.DispatchFailure
import opensamguk.logic.input.DispatchStatus
import opensamguk.logic.input.HwihaPhase

data class DispatchPendingResponse(val result: Boolean, val code: DispatchFailure? = null,
    val now: HwihaPhase? = null, val dispatches: List<DispatchPendingItem> = emptyList(),
    val queued: DispatchQueuedItem? = null)
data class DispatchPendingItem(val dispatchId: String, val issuerId: Int, val targetId: Int, val countyId: Int,
    val issuedAt: HwihaPhase, val dueAt: HwihaPhase, val status: DispatchStatus,
    val currentFailure: DispatchFailure? = null, val issuerLabel: String? = null,
    val targetLabel: String? = null, val countyLabel: String? = null)

data class DispatchQueuedItem(val requestId: String, val targetGeneralId: Int, val countyId: Int)
data class DispatchTargetOption(val generalId: Int, val label: String)
data class DispatchCountyOption(val countyId: Int, val label: String, val available: Boolean,
    val code: DispatchFailure? = null, val reason: String? = null)
data class DispatchOptionsResponse(val result: Boolean, val code: DispatchFailure? = null,
    val reason: String? = code?.message, val now: HwihaPhase? = null,
    val targets: List<DispatchTargetOption> = emptyList(),
    val counties: List<DispatchCountyOption> = emptyList(), val queued: DispatchQueuedItem? = null)
