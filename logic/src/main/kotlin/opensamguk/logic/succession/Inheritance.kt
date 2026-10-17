package opensamguk.logic.succession

/** Quantities are conserved per asset, not added across unrelated resource kinds. */
data class EstateAsset(val id: String, val kind: String, val quantity: Long) {
    init { require(id.isNotBlank() && kind.isNotBlank() && quantity > 0) }
}

enum class EstateOwnership { PERSONAL, FACTION }

sealed interface InheritanceDestination {
    data class Heir(val generalId: Long) : InheritanceDestination
    data class FactionReassignment(val nationId: Long) : InheritanceDestination
    data class Treasury(val nationId: Long, val treasuryId: String) : InheritanceDestination
    data class LocalEstate(val locationId: String) : InheritanceDestination
}

/** Null means not decided. Empty family order explicitly means there are no eligible relatives. */
data class InheritancePolicy(
    val ownershipByAsset: Map<String, EstateOwnership>? = null,
    val designatedHeirId: Long? = null,
    val familyPriority: List<Long>? = null,
    val treasuryId: String? = null,
) {
    init {
        require(designatedHeirId == null || designatedHeirId > 0)
        require(familyPriority == null || (familyPriority.all { it > 0 } && familyPriority.distinct() == familyPriority))
        require(treasuryId == null || treasuryId.isNotBlank())
    }
}

enum class InheritancePending { ASSET_CLASSIFICATION, FAMILY_PRIORITY, TREASURY_DEFINITION, FACTION_WITHOUT_NATION }
enum class InheritanceStatus { PLANNED, ALREADY_COMPLETED, PERMADEATH_DISABLED, NOT_DECIDED }

/** A routing plan only. Faction assets remain faction-owned awaiting reassignment, never an NPC bonus. */
class Inheritance private constructor(
    val case: SuccessionCase,
    val locationId: String,
    private val storedAssets: List<EstateAsset>,
    private val storedDestinations: Map<String, InheritanceDestination>,
) {
    constructor(case: SuccessionCase, locationId: String, assets: List<EstateAsset>) :
        this(case, locationId, assets.toList(), emptyMap())

    init {
        require(locationId.isNotBlank())
        require(storedAssets.map { it.id }.distinct().size == storedAssets.size)
    }

    val assets: List<EstateAsset> get() = storedAssets.toList()
    val destinations: Map<String, InheritanceDestination> get() = storedDestinations.toMap()
    val frozenAssets: List<EstateAsset> get() = storedAssets.filter { it.id !in storedDestinations }

    fun plan(policy: InheritancePolicy = InheritancePolicy()): InheritanceResult {
        if (case.allowPlayerPermadeath == false) return result(InheritanceStatus.PERMADEATH_DISABLED)
        if (case.allowPlayerPermadeath == null || case.permanentDeathEventId == null) {
            return result(InheritanceStatus.NOT_DECIDED)
        }
        if (storedDestinations.size == storedAssets.size) return result(InheritanceStatus.ALREADY_COMPLETED)
        require(policy.designatedHeirId != case.deceasedGeneralId)
        require(policy.familyPriority?.contains(case.deceasedGeneralId) != true)
        val destinations = storedDestinations.toMutableMap()
        val pending = linkedMapOf<String, InheritancePending>()
        for (asset in storedAssets) {
            if (asset.id in destinations) continue
            when (policy.ownershipByAsset?.get(asset.id)) {
                null -> pending[asset.id] = InheritancePending.ASSET_CLASSIFICATION
                EstateOwnership.FACTION -> {
                    val nationId = case.nationId
                    if (nationId == null) pending[asset.id] = InheritancePending.FACTION_WITHOUT_NATION
                    else destinations[asset.id] = InheritanceDestination.FactionReassignment(nationId)
                }
                EstateOwnership.PERSONAL -> {
                    val heirId = policy.designatedHeirId
                    val family = policy.familyPriority
                    when {
                        heirId != null -> destinations[asset.id] = InheritanceDestination.Heir(heirId)
                        family == null -> pending[asset.id] = InheritancePending.FAMILY_PRIORITY
                        family.isNotEmpty() -> destinations[asset.id] = InheritanceDestination.Heir(family.first())
                        case.nationId == null -> destinations[asset.id] = InheritanceDestination.LocalEstate(locationId)
                        policy.treasuryId == null -> pending[asset.id] = InheritancePending.TREASURY_DEFINITION
                        else -> destinations[asset.id] = InheritanceDestination.Treasury(case.nationId, policy.treasuryId)
                    }
                }
            }
        }
        val state = Inheritance(case, locationId, storedAssets, destinations.toMap())
        return InheritanceResult(if (pending.isEmpty()) InheritanceStatus.PLANNED else InheritanceStatus.NOT_DECIDED,
            state, pending.toMap())
    }

    private fun result(status: InheritanceStatus) = InheritanceResult(status, this, emptyMap())
}

data class InheritanceResult(
    val status: InheritanceStatus,
    val state: Inheritance,
    val pending: Map<String, InheritancePending>,
)
