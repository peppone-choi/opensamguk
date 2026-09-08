package opensamguk.logic.world

data class BattlefieldMovementRequest(
    val topologyRevision: String,
    val topologyHash: String,
    val generalId: Int,
    val actorCityId: Int,
    val position: GeneralPositionState?,
    val expectedRevision: Long?,
    val catalogHash: String,
    val cityAnchors: Map<Int, StrategicNodeRef>,
)

enum class BattlefieldMovementDenial {
    UNKNOWN_SITE, STALE_CATALOG, STALE_REVISION, INVALID_POSITION, WRONG_NODE,
    WITHHELD, ALREADY_DEPLOYED, NOT_DEPLOYED, INVALID_ORIGIN, INVALID_RETURN,
}

sealed interface BattlefieldMovementResult {
    data class Allowed(val assessment: GeneralPositionAssessment) : BattlefieldMovementResult {
        val consumesMovementTurn: Boolean = true
    }
    data class Denied(val code: BattlefieldMovementDenial) : BattlefieldMovementResult
}

/** Same-node deployment only. Cross-province and naval ingress need separately reviewed movement. */
object BattlefieldMovementRules {
    private fun denied(code: BattlefieldMovementDenial) = BattlefieldMovementResult.Denied(code)

    private fun validate(catalog: BattlefieldCatalog, request: BattlefieldMovementRequest): BattlefieldMovementResult.Denied? {
        if (request.catalogHash != catalog.contentHash) return denied(BattlefieldMovementDenial.STALE_CATALOG)
        if (request.expectedRevision != request.position?.revision) return denied(BattlefieldMovementDenial.STALE_REVISION)
        if (request.generalId <= 0 || request.topologyRevision.isBlank() ||
            !request.topologyHash.matches(Regex("[0-9a-f]{64}"))) return denied(BattlefieldMovementDenial.INVALID_POSITION)
        request.position?.let {
            if (it.generalId != request.generalId || it.topologyRevision != request.topologyRevision ||
                it.topologyHash != request.topologyHash || it.revision == Long.MAX_VALUE) {
                return denied(BattlefieldMovementDenial.INVALID_POSITION)
            }
        }
        return null
    }

    fun enter(catalog: BattlefieldCatalog, siteId: String, request: BattlefieldMovementRequest): BattlefieldMovementResult {
        validate(catalog, request)?.let { return it }
        val site = catalog.entries[siteId] ?: return denied(BattlefieldMovementDenial.UNKNOWN_SITE)
        val node = site.node ?: return denied(BattlefieldMovementDenial.WITHHELD)
        if (request.position?.battlefield != null) return denied(BattlefieldMovementDenial.ALREADY_DEPLOYED)
        if (request.actorCityId != site.ingressCityId || request.cityAnchors[request.actorCityId] != node) {
            return denied(BattlefieldMovementDenial.INVALID_ORIGIN)
        }
        if (request.position != null && request.position.node != node) return denied(BattlefieldMovementDenial.WRONG_NODE)
        return BattlefieldMovementResult.Allowed(GeneralPositionAssessment(
            request.topologyRevision, request.topologyHash, request.generalId, node,
            BattlefieldPresence(site.id, catalog.contentHash, request.actorCityId),
        ))
    }

    fun exit(catalog: BattlefieldCatalog, request: BattlefieldMovementRequest): BattlefieldMovementResult {
        validate(catalog, request)?.let { return it }
        val position = request.position ?: return denied(BattlefieldMovementDenial.NOT_DEPLOYED)
        val presence = position.battlefield ?: return denied(BattlefieldMovementDenial.NOT_DEPLOYED)
        if (presence.catalogHash != catalog.contentHash) return denied(BattlefieldMovementDenial.STALE_CATALOG)
        val site = catalog.entries[presence.siteId] ?: return denied(BattlefieldMovementDenial.UNKNOWN_SITE)
        val node = site.node ?: return denied(BattlefieldMovementDenial.WITHHELD)
        if (position.node != node) return denied(BattlefieldMovementDenial.WRONG_NODE)
        if (presence.returnCityId != site.ingressCityId || request.actorCityId != presence.returnCityId ||
            request.cityAnchors[presence.returnCityId] != node) return denied(BattlefieldMovementDenial.INVALID_RETURN)
        return BattlefieldMovementResult.Allowed(GeneralPositionAssessment(
            request.topologyRevision, request.topologyHash, request.generalId, node,
        ))
    }
}

enum class BattlefieldAction { BATTLEFIELD_MOVEMENT, REST, CITY_LOCAL }

fun battlefieldAllowsCityLocalAction(presence: BattlefieldPresence?, action: BattlefieldAction): Boolean =
    presence == null || action == BattlefieldAction.BATTLEFIELD_MOVEMENT || action == BattlefieldAction.REST
