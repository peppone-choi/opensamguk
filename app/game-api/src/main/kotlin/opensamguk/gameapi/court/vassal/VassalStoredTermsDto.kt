package opensamguk.gameapi.court.vassal

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.Phase
import opensamguk.logic.vassal.VassalAutonomy
import opensamguk.logic.vassal.VassalDiplomacyRight

enum class StoredVassalTermsStatus { READY, NOT_SEEDED, UNAVAILABLE }

/** Storage readiness only; this snapshot does not certify current contract activity or command access. */
data class StoredVassalTermsSnapshot(
    val status: StoredVassalTermsStatus,
    val now: Phase? = null,
    val contracts: List<VassalContractTermsDto> = emptyList(),
)

data class VassalContractTermsDto(
    val contractId: String,
    val sovereignLordId: Int,
    val vassalLordId: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val vassalName: String?,
    val fiefCountyIds: List<Int>,
    val tributePercent: Int,
    val reinforcementTroops: Int,
    val autonomy: List<VassalAutonomy>,
    val diplomacyRight: VassalDiplomacyRight,
    val loyalty: Int,
    val signedTurn: Long,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val expiresTurn: Long?,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val endedTurn: Long?,
    val tributeHistory: List<VassalTributeTermsDto>,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @get:JsonProperty("isHuman")
    val isHuman: Boolean? = null,
)

data class VassalTributeTermsDto(
    val year: Int,
    val month: Int,
    val due: Resources,
    val paid: Resources,
    val unpaid: Resources,
)
