package opensamguk.gameapi.court.vassal

import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase

/** Display of stored facts only: no activity, dates, pending settlement or request inference. */
object VassalHttpView {
    fun project(snapshot: StoredVassalTermsSnapshot): VassalHttpDto {
        if (snapshot.status != StoredVassalTermsStatus.READY || snapshot.now == null) {
            return VassalHttpDto(VassalHttpStatus.UNAVAILABLE, snapshot.now, snapshot.status)
        }
        val rows = snapshot.contracts.map { terms ->
            VassalDisplayDto(terms,
                if (terms.isHuman == null) VassalFieldStatus.UNAVAILABLE else VassalFieldStatus.READY,
                monthly(terms, snapshot.now))
        }
        return VassalHttpDto(VassalHttpStatus.PARTIAL, snapshot.now, snapshot.status, rows)
    }

    private fun monthly(terms: VassalContractTermsDto, now: Phase): MonthlyTributeDto {
        val receipt = terms.tributeHistory.singleOrNull { it.year == now.year && it.month == now.month }
            ?: return MonthlyTributeDto(MonthlyTributeStatus.NO_RECEIPT, null)
        val status = when {
            receipt.unpaid != Resources() -> MonthlyTributeStatus.UNPAID
            receipt.due == Resources() -> MonthlyTributeStatus.ZERO_DUE
            else -> MonthlyTributeStatus.PAID
        }
        return MonthlyTributeDto(status, receipt)
    }
}
