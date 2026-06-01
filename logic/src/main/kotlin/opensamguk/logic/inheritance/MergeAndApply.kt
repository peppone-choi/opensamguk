package opensamguk.logic.inheritance

import kotlinx.serialization.json.Json
import opensamguk.logic.inheritance.InheritanceKey.Companion.keyName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

private val json = Json {}

internal fun parseYearMonth(s: String): Pair<Int, Int> {
    val parts = s.split("-")
    return parts[0].toInt() to parts[1].toInt()
}

fun mergeTotalInheritancePoint(
    general: GeneralProxy,
    isRebirth: Boolean,
    isUnited: Boolean,
    year: Int,
    startYear: Int,
    month: Int,
    store: InheritancePointStore,
    serverID: Int,
    resultSink: (InheritanceResultRow) -> Unit,
) {
    if (general.owner == 0) return
    if (general.npc > 1) return

    if (general.npc == 1 && !isRebirth) {
        val pickYearMonth = general.getAuxVar("pickYearMonth")
        if (pickYearMonth != null) {
            val pickYear = parseYearMonth(pickYearMonth.toString()).first
            if ((year - pickYear) * 2 <= (year - startYear)) {
                return
            }
        }
    }

    for ((key, type) in InheritanceKeyRegistry.entries) {
        if (type.storeType == true) continue
        val point = InheritancePointMath.compute(general, key, isUnited, forceCalc = true) ?: continue
        store.putRaw(general.owner, key.keyName(), point, null)
    }

    val all = store.getAll(general.owner)
    val valueJson = buildJsonObject {
        for ((k, v) in all) {
            putJsonArray(k) {
                add(v.first)
                when (val aux = v.second) {
                    null -> add(JsonNull)
                    is Number -> add(aux)
                    is String -> add(aux)
                    is Boolean -> add(aux)
                    else -> add(aux.toString())
                }
            }
        }
    }.let { json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }

    resultSink(
        InheritanceResultRow(
            serverID = serverID,
            ownerID = general.owner,
            generalID = general.id,
            year = year,
            month = month,
            valueJson = valueJson,
        )
    )
}

fun applyInheritanceUser(
    ownerID: Int,
    isRebirth: Boolean,
    store: InheritancePointStore,
    userLogSink: (String, String) -> Unit,
): Int {
    if (ownerID == 0) return 0

    val allPoints = store.getAll(ownerID)
    if (allPoints.isEmpty()) return 0
    if (allPoints.size == 1 && allPoints.containsKey(InheritanceKey.PREVIOUS.keyName())) {
        return (allPoints[InheritanceKey.PREVIOUS.keyName()]?.first ?: 0.0).toInt()
    }

    val previousPoint = allPoints[InheritanceKey.PREVIOUS.keyName()]?.first ?: 0.0
    val keepValues = mutableMapOf<String, Pair<Double, Any?>>()
    var total = 0.0

    for ((rKey, pair) in allPoints) {
        val key = try {
            InheritanceKey.valueOf(rKey.uppercase())
        } catch (e: IllegalArgumentException) {
            continue
        }
        val keyTypeObj = InheritanceKeyRegistry[key]
        var value = pair.first

        if (isRebirth) {
            if (keyTypeObj.rebirthStoreCoeff == null) {
                keepValues[rKey] = pair
                continue
            }
            value *= keyTypeObj.rebirthStoreCoeff
        }

        userLogSink("${keyTypeObj.info} 포인트 $value 증가", "inheritPoint")
        total += value
    }

    val totalPoint = total.toInt()
    userLogSink("포인트 $previousPoint => $totalPoint", "inheritPoint")

    store.clear(ownerID)
    for ((k, v) in keepValues) {
        store.putRaw(ownerID, k, v.first, v.second)
    }
    store.putRaw(ownerID, InheritanceKey.PREVIOUS.keyName(), totalPoint.toDouble(), null)

    return totalPoint
}
