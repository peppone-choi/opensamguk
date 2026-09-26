package opensamguk.logic.vision

import opensamguk.logic.input.*


import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.logic.world.Commandery
import opensamguk.logic.world.CommanderyIndex
import opensamguk.logic.world.StrategicNodeRef
import java.security.MessageDigest

/** Direct-action scouting (§12.1): the actor looks into one commandery next to where they stand. */
data class ScoutInput(val actorId: Int, val commanderyId: String) {
    init { require(actorId > 0 && commanderyId.isNotBlank() && commanderyId.length <= 128) }
}

object ScoutInputCodec {
    const val INPUT_ID = "action.scout"

    /** Exactly `{"commanderyId":"<parentRegions id>"}`; the id is stable across number reorders. */
    fun parse(actorId: Int, rawJson: String?): ScoutInput? {
        if (actorId <= 0 || rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("commanderyId")) return null
            val id = fields["commanderyId"] as? JsonPrimitive ?: return null
            if (!id.isString || id.content.isBlank() || id.content.length > 128) return null
            ScoutInput(actorId, id.content)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(input: ScoutInput): String = buildJsonObject { put("commanderyId", input.commanderyId) }.toString()
}

enum class ScoutFailure {
    WRONG_RULE_PROFILE, INVALID_INPUT, POSITION_UNAVAILABLE, UNKNOWN_COMMANDERY, NOT_ADJACENT, STATE_UNAVAILABLE,
}

sealed interface ScoutAssessment {
    data class Eligible(val origin: Commandery, val target: Commandery) : ScoutAssessment
    data class Rejected(val reason: ScoutFailure) : ScoutAssessment
}

/** Shared by the API precheck, the reservation admission and the personal-turn re-check (§5 contract). */
object ScoutRules {
    fun assess(profile: RuleProfile, actorNode: StrategicNodeRef?, commanderyId: String, index: CommanderyIndex): ScoutAssessment {
        if (profile != RuleProfile.HWIHA) return ScoutAssessment.Rejected(ScoutFailure.WRONG_RULE_PROFILE)
        val origin = index.commanderyOf(actorNode) ?: return ScoutAssessment.Rejected(ScoutFailure.POSITION_UNAVAILABLE)
        val target = index.byId(commanderyId) ?: return ScoutAssessment.Rejected(ScoutFailure.UNKNOWN_COMMANDERY)
        if (!index.adjacent(origin, target.no)) return ScoutAssessment.Rejected(ScoutFailure.NOT_ADJACENT)
        return ScoutAssessment.Eligible(index.commanderies[origin], target)
    }

    fun reason(failure: ScoutFailure): String = when (failure) {
        ScoutFailure.WRONG_RULE_PROFILE -> "이 월드의 규칙에서 사용할 수 없는 입력입니다."
        ScoutFailure.INVALID_INPUT -> "첩보 대상 郡國을 확인할 수 없습니다."
        ScoutFailure.POSITION_UNAVAILABLE -> "장수가 육지 省에 있지 않아 첩보할 수 없습니다."
        ScoutFailure.UNKNOWN_COMMANDERY -> "지도에 없는 郡國입니다."
        ScoutFailure.NOT_ADJACENT -> "지금 서 있는 郡國과 맞닿은 郡國만 첩보할 수 있습니다."
        ScoutFailure.STATE_UNAVAILABLE -> "지도·군단 상태를 확인할 수 없어 첩보할 수 없습니다."
    }
}

/** A city seen at scouting time: controller then, and whether a county warehouse exists. */
data class ScoutedCity(val cityId: Int, val nationId: Int, val warehouse: Boolean) {
    init { require(cityId > 0 && nationId >= 0) }
}

/** A corps seen at scouting time. Troops are stored only as a band code, never the exact count. */
data class ScoutedCorps(
    val corpsKey: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val nationId: Int,
    val provinceId: String,
    val troopsBand: String,
) {
    init {
        require(corpsKey.matches(Regex("[0-9a-f]{16}")))
        require(ownerGeneralId > 0 && commanderGeneralId > 0 && nationId >= 0)
        require(provinceId.isNotBlank() && troopsBand.isNotBlank())
    }
}

data class ScoutReport(
    val commanderyId: String,
    val seenAt: Phase,
    val cities: List<ScoutedCity>,
    val corps: List<ScoutedCorps>,
) {
    init {
        require(commanderyId.isNotBlank())
        require(cities.map { it.cityId } == cities.map { it.cityId }.distinct().sorted()) { "Scouted cities must be unique and ordered" }
        require(corps.map { it.corpsKey } == corps.map { it.corpsKey }.distinct().sorted()) { "Scouted corps must be unique and ordered" }
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "commanderyId" to commanderyId,
        "seenAt" to seenAt.toMetaValue(),
        "cities" to cities.map { linkedMapOf("cityId" to it.cityId, "nationId" to it.nationId, "warehouse" to it.warehouse) },
        "corps" to corps.map {
            linkedMapOf("corpsKey" to it.corpsKey, "ownerGeneralId" to it.ownerGeneralId,
                "commanderGeneralId" to it.commanderGeneralId, "nationId" to it.nationId,
                "provinceId" to it.provinceId, "troopsBand" to it.troopsBand)
        },
    )
}

/**
 * The actor's private scouting notebook — one snapshot per commandery, replaced only by scouting again.
 * Bound to the tiles content hash: a map change orphans commandery identities, so a notebook written against
 * other tiles is unusable rather than silently reinterpreted.
 */
data class ScoutReports(val tilesContentHash: String, val reports: List<ScoutReport>) {
    init {
        require(tilesContentHash.matches(Regex("[0-9a-f]{64}")))
        require(reports.map { it.commanderyId } == reports.map { it.commanderyId }.distinct().sorted()) {
            "Scout reports must be unique per commandery and ordered"
        }
    }

    fun with(report: ScoutReport): ScoutReports =
        copy(reports = (reports.filterNot { it.commanderyId == report.commanderyId } + report).sortedBy { it.commanderyId })

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "tilesContentHash" to tilesContentHash, "reports" to reports.map { it.toMetaValue() })

    companion object {
        const val META_KEY = "scoutReports"

        /** @throws IllegalArgumentException on a malformed notebook (the caller decides how to fail closed). */
        fun read(meta: Map<String, Any?>): ScoutReports? {
            if (META_KEY !in meta) return null
            val root = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(root.keys == setOf("version", "tilesContentHash", "reports") && root["version"] == 1) { "Invalid scout reports schema" }
            val rows = root["reports"] as? List<*> ?: invalid()
            return ScoutReports(root["tilesContentHash"] as? String ?: invalid(), rows.map { raw ->
                val row = raw as? Map<*, *> ?: invalid()
                require(row.keys == setOf("commanderyId", "seenAt", "cities", "corps"))
                ScoutReport(
                    row["commanderyId"] as? String ?: invalid(),
                    Phase.read(row["seenAt"]),
                    (row["cities"] as? List<*> ?: invalid()).map { item ->
                        val city = item as? Map<*, *> ?: invalid()
                        require(city.keys == setOf("cityId", "nationId", "warehouse"))
                        ScoutedCity(city["cityId"] as? Int ?: invalid(), city["nationId"] as? Int ?: invalid(),
                            city["warehouse"] as? Boolean ?: invalid())
                    },
                    (row["corps"] as? List<*> ?: invalid()).map { item ->
                        val corps = item as? Map<*, *> ?: invalid()
                        require(corps.keys == setOf("corpsKey", "ownerGeneralId", "commanderGeneralId", "nationId", "provinceId", "troopsBand"))
                        ScoutedCorps(corps["corpsKey"] as? String ?: invalid(), corps["ownerGeneralId"] as? Int ?: invalid(),
                            corps["commanderGeneralId"] as? Int ?: invalid(), corps["nationId"] as? Int ?: invalid(),
                            corps["provinceId"] as? String ?: invalid(), corps["troopsBand"] as? String ?: invalid())
                    },
                )
            })
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid scout reports")
    }
}

/** Facts about one city that the capture needs; the caller resolves the city → province binding. */
data class ScoutCityFact(val cityId: Int, val provinceId: String, val nationId: Int, val warehouse: Boolean)

object ScoutCapture {
    /**
     * Opaque, deterministic corps identity for other viewers. A raw order id is the owner's request id and is
     * never shown to anyone else.
     */
    fun corpsKey(orderId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("corps:$orderId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    /**
     * Snapshot of [target] now. Corps positions come from the commander's authoritative position; corps whose
     * relationships no longer hold (see [DeploymentRules.assessActive]) are not reported as seen.
     */
    fun capture(
        target: Commandery,
        index: CommanderyIndex,
        cities: List<ScoutCityFact>,
        projection: DeploymentProjection,
        rules: VisionRules.Rules,
        now: Phase,
    ): ScoutReport {
        val seenCities = cities.filter { index.commanderyOf(it.provinceId) == target.no }
            .map { ScoutedCity(it.cityId, it.nationId, it.warehouse) }.sortedBy { it.cityId }
        val nodes = projection.people.associate { it.id to it.node }
        val seenCorps = projection.deployed.mapNotNull { corps ->
            val node = nodes[corps.commanderGeneralId] as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            if (index.commanderyOf(node.id) != target.no) return@mapNotNull null
            if (DeploymentRules.assessActive(corps, projection) !is DeploymentAssessment.Eligible) return@mapNotNull null
            ScoutedCorps(corpsKey(corps.orderId), corps.ownerGeneralId, corps.commanderGeneralId, corps.nationId, node.id,
                rules.band(CorpsTroops.of(corps, projection)).code)
        }.sortedBy { it.corpsKey }
        return ScoutReport(target.id, now, seenCities, seenCorps)
    }
}

object CorpsTroops {
    /** Live troops of a corps: the sum of its unit cards' current troops (never copied into the deployment). */
    fun of(corps: DeployedCorps, projection: DeploymentProjection): Int =
        corps.bugokIds.sumOf { id -> projection.units.singleOrNull { it.id == id }?.troops?.coerceAtLeast(0) ?: 0 }
}
