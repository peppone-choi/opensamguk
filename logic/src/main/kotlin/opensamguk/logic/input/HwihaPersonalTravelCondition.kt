package opensamguk.logic.input

import java.math.BigInteger
import opensamguk.logic.world.LandMarchCursor
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.ResolvedLandMarchPath

/** Personal stamina and spirit used by direct forced march; independent of city troops and bugok. */
data class HwihaPersonalTravelCondition(val fatigue: Int, val morale: Int,
    val forcedDistanceRemainderMm: Long = 0) {
    init { require(fatigue in 0..100 && morale in 0..100 && forcedDistanceRemainderMm in 0 until 30_000_000L) }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "fatigue" to fatigue, "morale" to morale,
        "forcedDistanceRemainderMm" to forcedDistanceRemainderMm)

    fun afterForcedMarch(previous: LandMarchCursor, next: LandMarchCursor,
        path: ResolvedLandMarchPath, metrics: LandMarchMetricSnapshot): HwihaPersonalTravelCondition {
        val beforeMm = HwihaPersonalTravelDistance.at(path, previous, metrics)
        val afterMm = HwihaPersonalTravelDistance.at(path, next, metrics)
        require(afterMm >= beforeMm)
        val cumulative = Math.addExact(forcedDistanceRemainderMm, afterMm - beforeMm)
        val fatigueGain = HwihaPersonalTravelDistance.points(cumulative, 10) -
            HwihaPersonalTravelDistance.points(forcedDistanceRemainderMm, 10)
        val moraleLoss = HwihaPersonalTravelDistance.points(cumulative, 5) -
            HwihaPersonalTravelDistance.points(forcedDistanceRemainderMm, 5)
        return HwihaPersonalTravelCondition((fatigue.toLong() + fatigueGain).coerceAtMost(100).toInt(),
            (morale.toLong() - moraleLoss).coerceAtLeast(0).toInt(), cumulative % 30_000_000L)
    }

    companion object {
        const val META_KEY = "hwihaPersonalTravelCondition"
        val INITIAL = HwihaPersonalTravelCondition(0, 100)

        fun read(meta: Map<String, Any?>): HwihaPersonalTravelCondition? {
            if (META_KEY !in meta) return null
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == setOf("version", "fatigue", "morale", "forcedDistanceRemainderMm") && value["version"] == 1)
            val remainder = when (val raw = value["forcedDistanceRemainderMm"]) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> invalid()
            }
            return HwihaPersonalTravelCondition(value["fatigue"] as? Int ?: invalid(),
                value["morale"] as? Int ?: invalid(), remainder)
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid personal travel condition")
    }
}

/** Cumulative physical distance avoids rounding away short partial steps across phases. */
object HwihaPersonalTravelDistance {
    private const val THIRTY_KM_MM = 30_000_000L

    fun at(path: ResolvedLandMarchPath, cursor: LandMarchCursor, metrics: LandMarchMetricSnapshot): Long {
        require(cursor.pathHash == path.pathHash && cursor.edgeIndex <= path.edgeIds.size)
        val complete = path.edgeIds.take(cursor.edgeIndex).fold(0L) { total, id ->
            Math.addExact(total, metrics.edgesById.getValue(id).distanceMm)
        }
        if (cursor.edgeIndex == path.edgeIds.size) return complete
        val edge = metrics.edgesById.getValue(path.edgeIds[cursor.edgeIndex])
        require(cursor.paidMm in 0 until edge.costMm)
        val partial = BigInteger.valueOf(edge.distanceMm).multiply(BigInteger.valueOf(cursor.paidMm))
            .divide(BigInteger.valueOf(edge.costMm)).longValueExact()
        return Math.addExact(complete, partial)
    }

    fun points(distanceMm: Long, pointsPerThirtyKm: Int): Long {
        require(distanceMm >= 0 && pointsPerThirtyKm >= 0)
        return BigInteger.valueOf(distanceMm).multiply(BigInteger.valueOf(pointsPerThirtyKm.toLong()))
            .divide(BigInteger.valueOf(THIRTY_KM_MM)).longValueExact()
    }
}
