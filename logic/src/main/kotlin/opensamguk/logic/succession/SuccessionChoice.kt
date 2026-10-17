package opensamguk.logic.succession

/** Confirmed permanent-death event only; neither HP zero nor deadYear is interpreted here. */
data class SuccessionCase(
    val accountId: Long,
    val worldId: Long,
    val deceasedGeneralId: Long,
    val nationId: Long?,
    val permanentDeathEventId: String?,
    val allowPlayerPermadeath: Boolean?,
) {
    init {
        require(accountId > 0 && worldId > 0 && deceasedGeneralId > 0)
        require(nationId == null || nationId > 0)
        require(permanentDeathEventId == null || permanentDeathEventId.isNotBlank())
    }
}

enum class SuccessionOption { NEW_GENERAL, INHERIT }

data class SuccessionRequest(val requestId: String, val option: SuccessionOption, val targetId: Long? = null) {
    init {
        require(requestId.isNotBlank())
        require(if (option == SuccessionOption.INHERIT) targetId != null && targetId > 0 else targetId == null)
    }
}

/** A candidate snapshot, not a mutation of an existing general or of the account. */
data class SuccessionNpc(
    val generalId: Long,
    val nationId: Long?,
    val directLordId: Long?,
    val playerAccountId: Long?,
    val abilities: Map<String, Int>,
    val relationships: Map<Long, Int>,
    val ownedUnitIds: Set<String>,
    val personalAssetIds: Set<String>,
) {
    init {
        require(generalId > 0)
        require(nationId == null || nationId > 0)
        require(directLordId == null || directLordId > 0)
        require(playerAccountId == null || playerAccountId > 0)
        require(abilities.keys.none { it.isBlank() } && relationships.keys.all { it > 0 })
        require(ownedUnitIds.none { it.isBlank() } && personalAssetIds.none { it.isBlank() })
    }

    internal fun snapshot() = copy(abilities = abilities.toMap(), relationships = relationships.toMap(),
        ownedUnitIds = ownedUnitIds.toSet(), personalAssetIds = personalAssetIds.toSet())
}

data class SuccessionSelection(val request: SuccessionRequest, val inheritedNpc: SuccessionNpc?)

enum class SuccessionStatus {
    SELECTED, ALREADY_COMPLETED, CONFLICT, ACCOUNT_MISMATCH, INVALID_TARGET,
    NEW_GENERAL_NOT_ALLOWED, PERMADEATH_DISABLED, NOT_DECIDED,
}

/** The caller persists this state atomically with the account binding; no persistence occurs here. */
class SuccessionChoice private constructor(
    val case: SuccessionCase,
    private val storedSelection: SuccessionSelection?,
) {
    constructor(case: SuccessionCase) : this(case, null)

    val selection: SuccessionSelection?
        get() = storedSelection?.copy(inheritedNpc = storedSelection.inheritedNpc?.snapshot())

    fun choose(
        requesterAccountId: Long,
        request: SuccessionRequest,
        candidates: List<SuccessionNpc> = emptyList(),
        newGeneralEligible: Boolean? = null,
    ): SuccessionChoiceResult {
        if (requesterAccountId != case.accountId) return result(SuccessionStatus.ACCOUNT_MISMATCH)
        if (case.allowPlayerPermadeath == false) return result(SuccessionStatus.PERMADEATH_DISABLED)
        if (case.allowPlayerPermadeath == null || case.permanentDeathEventId == null) {
            return result(SuccessionStatus.NOT_DECIDED)
        }
        storedSelection?.let {
            return result(if (it.request == request) SuccessionStatus.ALREADY_COMPLETED else SuccessionStatus.CONFLICT)
        }
        val npc = when (request.option) {
            SuccessionOption.NEW_GENERAL -> {
                if (newGeneralEligible == null) return result(SuccessionStatus.NOT_DECIDED)
                if (!newGeneralEligible) return result(SuccessionStatus.NEW_GENERAL_NOT_ALLOWED)
                null
            }
            SuccessionOption.INHERIT -> {
                val candidate = candidates.singleOrNull { it.generalId == request.targetId }
                    ?: return result(SuccessionStatus.INVALID_TARGET)
                if (candidate.generalId == case.deceasedGeneralId || candidate.playerAccountId != null ||
                    candidate.directLordId != case.deceasedGeneralId || candidate.nationId != case.nationId) {
                    return result(SuccessionStatus.INVALID_TARGET)
                }
                candidate.snapshot()
            }
        }
        return SuccessionChoiceResult(
            SuccessionStatus.SELECTED, SuccessionChoice(case, SuccessionSelection(request, npc)),
        )
    }

    private fun result(status: SuccessionStatus) = SuccessionChoiceResult(status, this)
}

data class SuccessionChoiceResult(val status: SuccessionStatus, val state: SuccessionChoice)
