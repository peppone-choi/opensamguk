package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import opensamguk.logic.world.Commandery
import opensamguk.logic.world.CommanderyIndex
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/**
 * Builds the vision [CommanderyIndex] from the selected world's pinned tiles bytes.
 *
 * - Numbers are `parentRegions` indices; `juns[i]` must name the same 郡國 so the number equals the provinces
 *   PNG commandery channel (`tools/map/build_province_map.py` writes `parentOwner` → `parentRegions`).
 * - Province → commandery is `provinceRecords[].parentRegionId`, covering exactly the topology's land provinces.
 * - Adjacency is derived from the owner raster (4-neighbour shared border, sea excluded) and, when the tiles carry
 *   `adjacency.commandery`, must equal it — two independent derivations of the same graph.
 */
object CommanderyIndexJson {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun load(topology: StrategicTopologySnapshot, tilesBytes: ByteArray): CommanderyIndex {
        val hash = MessageDigest.getInstance("SHA-256").digest(tilesBytes).joinToString("") { "%02x".format(it) }
        require(topology.artifactHashes[LandMarchMetricSnapshot.TILES_PATH] == hash) { "Commandery tiles differ from topology pin" }
        return parse(tilesBytes, topology.landProvinceIds, hash)
    }

    /** Pure parse, split out so tests can probe the cross-checks without a matching topology pin. */
    internal fun parse(tilesBytes: ByteArray, landProvinceIds: Set<String>, hash: String): CommanderyIndex {
        val root = try { mapper.readTree(tilesBytes) } catch (e: java.io.IOException) {
            throw IllegalArgumentException("Malformed commandery tiles JSON", e)
        }
        require(root != null && root.isObject) { "Commandery tiles must be an object" }
        val parents = array(root.path("parentRegions"))
        val juns = array(root.path("juns"))
        require(parents.isNotEmpty() && parents.size == juns.size) { "parentRegions and juns must align" }
        val commanderies = parents.mapIndexed { index, parent ->
            require(text(parent.path("nameCh")) == text(juns[index].path("nameCh"))) { "juns[$index] names a different commandery" }
            Commandery(index, text(parent.path("id")), text(parent.path("displayName")), text(parent.path("nameCh")))
        }
        val numberById = commanderies.associate { it.id to it.no }
        require(numberById.size == commanderies.size) { "Duplicate parent region id" }
        val provinces = array(root.path("provinceRecords"))
        val provinceIds = provinces.map { text(it.path("id")) }
        require(provinceIds.toSet().size == provinceIds.size && provinceIds.toSet() == landProvinceIds) {
            "Commandery provinces differ from topology"
        }
        val provinceCommandery = provinces.map { record ->
            requireNotNull(numberById[text(record.path("parentRegionId"))]) { "Province ${record.path("id")} has an unknown parent" }
        }
        val meta = root.path("_meta")
        val cols = integer(meta.path("cols")); val rows = integer(meta.path("rows"))
        require(cols > 0 && rows > 0 && cols.toLong() * rows <= Int.MAX_VALUE) { "Invalid raster dimensions" }
        val cells = IntArray(cols * rows)
        var offset = 0
        for (run in array(root.path("owner"))) {
            require(run.isArray && run.size() == 2) { "Invalid owner RLE pair" }
            val owner = integer(run[0]); val length = integer(run[1])
            require(owner == -1 || owner in provinces.indices) { "Unknown owner province index" }
            require(length > 0 && offset.toLong() + length <= cells.size) { "Invalid owner RLE length" }
            val value = if (owner < 0) -1 else provinceCommandery[owner]
            cells.fill(value, offset, offset + length)
            offset += length
        }
        require(offset == cells.size) { "Owner RLE does not cover raster" }
        val pairs = sortedSetOf<Pair<Int, Int>>(compareBy({ it.first }, { it.second }))
        for (index in cells.indices) {
            val here = cells[index]
            if (here < 0) continue
            val col = index % cols
            if (col + 1 < cols) cells[index + 1].let { if (it >= 0 && it != here) pairs += minOf(here, it) to maxOf(here, it) }
            if (index + cols < cells.size) cells[index + cols].let { if (it >= 0 && it != here) pairs += minOf(here, it) to maxOf(here, it) }
        }
        val declared = root.path("adjacency").path("commandery")
        if (!declared.isMissingNode) {
            val listed = array(declared).map { row ->
                val a = integer(row.path("a")); val b = integer(row.path("b"))
                minOf(a, b) to maxOf(a, b)
            }.toSortedSet(compareBy({ it.first }, { it.second }))
            require(listed == pairs) { "Declared commandery adjacency differs from the owner raster" }
        }
        return CommanderyIndex(hash, commanderies, provinceIds.zip(provinceCommandery).toMap(), pairs.toSet())
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
