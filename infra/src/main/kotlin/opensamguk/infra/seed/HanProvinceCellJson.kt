package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import opensamguk.logic.world.*

/** The caller supplies bytes from its selected ResolvedHanWorldArtifacts; no filesystem fallback. */
object HanProvinceCellJson {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val terrainKinds = setOf("SEA", "PLAIN", "MOUNTAIN", "RIVER", "LAKE", "DESERT", "PLATEAU", "BASIN", "HILL", "OUT_OF_SCOPE")

    fun load(topology: StrategicTopologySnapshot, tilesBytes: ByteArray): HanProvinceCellIndex {
        val hash = MessageDigest.getInstance("SHA-256").digest(tilesBytes).joinToString("") { "%02x".format(it) }
        require(topology.artifactHashes[LandMarchMetricSnapshot.TILES_PATH] == hash) { "Province cells differ from topology tiles pin" }
        val root = try { mapper.readTree(tilesBytes) } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Malformed province tiles JSON", e)
        }
        require(root != null && root.isObject) { "Province tiles must be an object" }
        val meta = root.path("_meta")
        val cols = integer(meta.path("cols")); val rows = integer(meta.path("rows"))
        require(cols > 0 && rows > 0 && cols.toLong() * rows <= Int.MAX_VALUE) { "Invalid raster dimensions" }
        val count = cols * rows
        val ids = array(root.path("provinceRecords")).map { text(it.path("id")) }
        require(ids.toSet().size == ids.size && ids.toSet() == topology.landProvinceIds) { "Province identities differ from topology" }
        val legendNode = meta.path("terrainLegend")
        require(legendNode.isObject && !legendNode.isEmpty) { "Missing terrain legend" }
        val legend = legendNode.fields().asSequence().associate { (key, value) ->
            require(key.length == 1 && key[0] in '0'..'9') { "Invalid terrain code" }
            val kind = text(value); require(kind in terrainKinds) { "Unknown terrain kind" }
            key[0] to kind
        }
        val terrain = array(root.path("terrain")).map(::text)
        require(terrain.size == rows && terrain.all { it.length == cols && it.all(legend::containsKey) }) { "Invalid terrain raster" }
        val cells = ids.associateWithTo(linkedMapOf()) { mutableListOf<HanProvinceCell>() }
        var offset = 0
        for (run in array(root.path("owner"))) {
            require(run.isArray && run.size() == 2) { "Invalid owner RLE pair" }
            val owner = integer(run[0]); val length = integer(run[1])
            require(owner == -1 || owner in ids.indices) { "Unknown owner province index" }
            require(length > 0 && offset.toLong() + length <= count) { "Invalid owner RLE length" }
            if (owner >= 0) repeat(length) { step ->
                val index = offset + step; val col = index % cols; val row = index / cols
                cells.getValue(ids[owner]).add(HanProvinceCell(col, row, terrain[row][col]))
            }
            offset += length
        }
        require(offset == count) { "Owner RLE does not cover raster" }
        return HanProvinceCellIndex(topology.topologyRevision, topology.contentHash, hash, cols, rows, legend, cells)
    }

    private fun array(node: JsonNode): List<JsonNode> {
        require(node.isArray) { "Expected tile array" }
        return node.toList()
    }
    private fun integer(node: JsonNode): Int {
        require(node.isIntegralNumber && node.canConvertToInt()) { "Expected bounded tile integer" }
        return node.intValue()
    }
    private fun text(node: JsonNode): String {
        require(node.isTextual && node.textValue().isNotBlank()) { "Expected tile text" }
        return node.textValue()
    }
}
