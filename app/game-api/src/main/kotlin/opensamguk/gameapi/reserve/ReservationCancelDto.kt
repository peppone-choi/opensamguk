package opensamguk.gameapi.reserve

import opensamguk.common.wire.CommandLifecycleResult

data class ReservationCancelDto(
    val requestId: String,
    val result: CommandLifecycleResult,
    val committedWorldVersion: Long,
    val status: String = "RESOLVED",
    val type: String = "reservationCancelled",
    val ok: Boolean = true,
    val accepted: Boolean = true,
    val receiptRecorded: Boolean = true,
)

class ReservationCancelRejected(val code: String, val httpStatus: Int = 409, val retryable: Boolean = false) :
    RuntimeException(code)
