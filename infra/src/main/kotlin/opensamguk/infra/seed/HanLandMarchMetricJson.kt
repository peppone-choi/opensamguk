package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/** Pinned geometry only. The topology supplies every edge; county adjacency is never consulted. */
object HanLandMarchMetricJson {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val water = setOf("SEA", "LAKE", "OUT_OF_SCOPE")
    private val rough = setOf("MOUNTAIN", "PLATEAU", "HILL", "DESERT")
    private val terrainKinds = water + rough + setOf("PLAIN", "RIVER", "BASIN")

    fun load(topology: StrategicTopologySnapshot, tilesBytes: ByteArray): LandMarchMetricSnapshot {
        val hash = MessageDigest.getInstance("SHA-256").digest(tilesBytes).joinToString("") { "%02x".format(it) }
        require(topology.artifactHashes[LandMarchMetricSnapshot.TILES_PATH] == hash) { "March tiles differ from topology pin" }
        val root = try { mapper.readTree(tilesBytes) } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Malformed march tiles JSON", e)
        }
        require(root != null && root.isObject) { "March tiles must be an object" }
        val meta = root.path("_meta")
        val cols = integer(meta.path("cols")); val rows = integer(meta.path("rows"))
        require(cols > 0 && rows > 0 && cols.toLong() * rows <= Int.MAX_VALUE) { "Invalid march raster dimensions" }
        val count = cols * rows
        val provinces = array(root.path("provinceRecords"))
        val ids = provinces.map { text(it.path("id")) }
        require(ids.toSet().size == ids.size && ids.toSet() == topology.landProvinceIds) { "March province identities differ from topology" }
        val legendNode = meta.path("terrainLegend")
        require(legendNode.isObject) { "Missing terrain legend" }
        val legend = legendNode.fields().asSequence().associate { (key, value) ->
            require(key.length == 1 && key[0] in '0'..'9') { "Invalid terrain code" }
            val kind = text(value)
            require(kind in terrainKinds) { "Unknown march terrain $kind" }
            key[0] to kind
        }
        val terrain = array(root.path("terrain")).map { text(it) }
        require(terrain.size == rows && terrain.all { it.length == cols && it.all(legend::containsKey) }) { "Invalid terrain raster" }
        val n = LongArray(ids.size); val sx = DoubleArray(ids.size); val sy = DoubleArray(ids.size); val r = LongArray(ids.size)
        var offset = 0
        for (run in array(root.path("owner"))) {
            require(run.isArray && run.size() == 2) { "Invalid owner RLE pair" }
            val owner = integer(run[0]); val length = integer(run[1])
            require(owner == -1 || owner in ids.indices) { "Unknown owner province index" }
            require(length > 0 && offset.toLong() + length <= count) { "Invalid owner RLE length" }
            repeat(length) {
                val x = offset % cols; val y = offset / cols
                val kind = legend.getValue(terrain[y][x])
                if (owner >= 0 && kind !in water) {
                    n[owner]++; sx[owner] += x; sy[owner] += y
                    if (kind in rough) r[owner]++
                }
                offset++
            }
        }
        require(offset == count) { "Owner RLE does not cover the raster" }
        data class Point(val x: Double, val y: Double, val lon: Double, val lat: Double)
        val points = array(root.path("cities")).mapNotNull { city ->
            require(city.isObject) { "Invalid calibration city record" }
            val fields = listOf("col", "row", "lon", "lat").map { city.path(it) }
            if (fields.all { it.isMissingNode || it.isNull }) return@mapNotNull null
            val x = integer(fields[0]); val y = integer(fields[1])
            val lon = number(fields[2]); val lat = number(fields[3])
            require(x in 0 until cols && y in 0 until rows && lon in -180.0..180.0 && lat in -90.0..90.0) { "Invalid calibration city" }
            Point(x.toDouble(), y.toDouble(), lon, lat)
        }
        val (_, dlon) = fit(points.map { it.x }, points.map { it.lon })
        val (lat0, dlat) = fit(points.map { it.y }, points.map { it.lat })
        val indices = ids.withIndex().associate { it.value to it.index }
        val metrics = topology.traversalEdges.filter(LandMarchMetricSnapshot::supports).map { edge ->
            val a = indices.getValue((edge.from as StrategicNodeRef.LandProvince).id)
            val b = indices.getValue((edge.to as StrategicNodeRef.LandProvince).id)
            require(n[a] > 0 && n[b] > 0) { "March edge has no non-water province geometry" }
            val x1 = sx[a] / n[a]; val y1 = sy[a] / n[a]
            val x2 = sx[b] / n[b]; val y2 = sy[b] / n[b]
            val lat = lat0 + ((y1 + y2) / 2) * dlat
            require(lat.isFinite() && lat in -90.0..90.0) { "Invalid calibrated march latitude" }
            val km = StrictMath.hypot((x2 - x1) * dlon * 111.32 * StrictMath.cos(StrictMath.toRadians(lat)),
                (y2 - y1) * dlat * 110.57)
            val share = (r[a].toDouble() / n[a] + r[b].toDouble() / n[b]) / 2
            LandMarchEdgeMetric(edge.id, millimetres(km), millimetres(km * (1 + 0.5 * share)))
        }
        return LandMarchMetricSnapshot(topology, hash, metrics)
    }

    private fun fit(xs: List<Double>, ys: List<Double>): Pair<Double, Double> {
        require(xs.size >= 2 && xs.size == ys.size) { "Insufficient calibration points" }
        val mx = xs.sum() / xs.size; val my = ys.sum() / ys.size
        var numerator = 0.0; var denominator = 0.0
        xs.indices.forEach { i ->
            numerator += (xs[i] - mx) * (ys[i] - my)
            denominator += (xs[i] - mx) * (xs[i] - mx)
        }
        require(denominator > 0 && denominator.isFinite()) { "Degenerate calibration axis" }
        val slope = numerator / denominator; val intercept = my - slope * mx
        require(slope.isFinite() && slope != 0.0 && intercept.isFinite()) { "Invalid calibration scale" }
        return intercept to slope
    }
    internal fun millimetres(km: Double): Long {
        val scaled = km * 1_000_000.0
        require(km > 0 && scaled.isFinite()) { "Non-positive or non-finite march distance" }
        val rounded = BigDecimal.valueOf(scaled).setScale(0, RoundingMode.HALF_UP)
        require(rounded > BigDecimal.ZERO && rounded <= BigDecimal.valueOf(Long.MAX_VALUE)) { "March distance rounds to zero or exceeds Long" }
        return rounded.longValueExact()
    }
    private fun array(node: JsonNode): List<JsonNode> { require(node.isArray) { "Expected march array" }; return node.toList() }
    private fun text(node: JsonNode): String { require(node.isTextual && node.textValue().isNotBlank()) { "Expected march text" }; return node.textValue() }
    private fun integer(node: JsonNode): Int { require(node.isIntegralNumber && node.canConvertToInt()) { "Expected march integer" }; return node.intValue() }
    private fun number(node: JsonNode): Double { require(node.isNumber && node.doubleValue().isFinite()) { "Expected finite march number" }; return node.doubleValue() }
}
