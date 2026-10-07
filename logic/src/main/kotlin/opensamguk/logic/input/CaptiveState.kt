package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A captive is held at an authoritative land province until an explicit release or enlistment. */
data class CaptiveState(
    val captorGeneralId: Int,
    val heldProvinceId: String,
    val capturedAt: Phase,
    val encounterId: String,
) {
    init {
        require(captorGeneralId > 0 && heldProvinceId.isNotBlank() && encounterId.isNotBlank())
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to VERSION,
        "captorGeneralId" to captorGeneralId,
        "heldProvinceId" to heldProvinceId,
        "capturedAt" to capturedAt.toMetaValue(),
        "encounterId" to encounterId,
        "expiry" to "NONE",
    )

    companion object {
        const val META_KEY = "captive"
        const val VERSION = 2

        /** Old and incomplete markers are never interpreted as an active custody grant. */
        fun read(meta: Map<String, Any?>): CaptiveState? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
            if (raw.keys != setOf("version", "captorGeneralId", "heldProvinceId", "capturedAt", "encounterId", "expiry") ||
                raw["version"] != VERSION || raw["expiry"] != "NONE") invalid()
            return try {
                CaptiveState(raw["captorGeneralId"] as? Int ?: invalid(),
                    raw["heldProvinceId"] as? String ?: invalid(),
                    Phase.read(raw["capturedAt"]), raw["encounterId"] as? String ?: invalid())
            } catch (_: IllegalArgumentException) { invalid() }
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("invalid captive state")
    }
}

data class CaptiveReleaseRequest(val actorId: Int, val targetGeneralId: Int)

/** Standing decision: it does not reserve or consume a personal turn. */
object CaptiveReleaseInput {
    const val INPUT_ID = "court.releaseCaptive"

    fun parse(actorId: Int, rawJson: String?): CaptiveReleaseRequest? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("targetGeneralId")) return null
            val target = fields["targetGeneralId"] as? JsonPrimitive ?: return null
            val id = if (!target.isString) target.intOrNull else null
            if (id == null || id <= 0 || id == actorId) null else CaptiveReleaseRequest(actorId, id)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: CaptiveReleaseRequest): String {
        require(request.actorId > 0 && request.targetGeneralId > 0 && request.actorId != request.targetGeneralId)
        return buildJsonObject { put("targetGeneralId", request.targetGeneralId) }.toString()
    }
}

/** The same live custody check is used by options, admission, and engine execution. */
object CaptiveReleaseRules {
    fun assess(request: CaptiveReleaseRequest, state: opensamguk.logic.domestic.DomesticProjection): PeopleFailure? {
        if (state.profile != RuleProfile.HWIHA) return PeopleFailure.WRONG_RULE_PROFILE
        val actor = state.person(request.actorId) ?: return PeopleFailure.ACTOR_NOT_FOUND
        if (actor.npcState == 5 || actor.nationId <= 0) return PeopleFailure.STATE_UNAVAILABLE
        val target = state.person(request.targetGeneralId) ?: return PeopleFailure.TARGET_NOT_CAPTIVE
        val marker = try { CaptiveState.read(target.meta) } catch (_: IllegalArgumentException) {
            return PeopleFailure.TARGET_NOT_CAPTIVE
        } ?: return PeopleFailure.TARGET_NOT_CAPTIVE
        if (marker.captorGeneralId != actor.id || target.npcState == 5) return PeopleFailure.TARGET_NOT_CAPTIVE
        val node = actor.node ?: return PeopleFailure.POSITION_UNAVAILABLE
        if (state.landProvinceIds?.contains(node) != true) return PeopleFailure.STATE_UNAVAILABLE
        if (actor.inBattle || target.inBattle) return PeopleFailure.BATTLE_PENDING
        if (node != marker.heldProvinceId || target.node != marker.heldProvinceId) return PeopleFailure.TARGET_UNAVAILABLE
        return null
    }
}
