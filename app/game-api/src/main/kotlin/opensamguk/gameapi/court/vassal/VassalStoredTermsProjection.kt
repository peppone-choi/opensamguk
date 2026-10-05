package opensamguk.gameapi.court.vassal

import opensamguk.logic.vassal.VassalState

/** Maps persisted terms, including ended records, without guessing the legacy Long time basis. */
object VassalStoredTermsView {
    fun project(state: VassalState, nationId: Int, names: Map<Int, String>): List<VassalContractTermsDto> {
        require(nationId > 0)
        val receipts = state.receipts.groupBy { it.contractId }
        return state.contracts.filter { it.nationId == nationId }.sortedBy { it.id }.map { contract ->
            VassalContractTermsDto(
                contract.id, contract.sovereignLordId, contract.vassalLordId,
                names[contract.vassalLordId]?.takeIf { it.isNotBlank() },
                contract.fiefCountyIds.sorted(), contract.tributePercent, contract.reinforcementTroops,
                contract.autonomy.sortedBy { it.name }, contract.diplomacyRight, contract.loyalty,
                contract.signedTurn, contract.expiresTurn, contract.endedTurn,
                receipts[contract.id].orEmpty().sortedWith(compareBy({ it.year }, { it.month })).map {
                    VassalTributeTermsDto(it.year, it.month, it.due, it.paid, it.unpaid)
                },
            )
        }
    }
}
