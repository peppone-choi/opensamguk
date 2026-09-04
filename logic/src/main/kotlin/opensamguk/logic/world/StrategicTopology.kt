package opensamguk.logic.world

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

enum class WaterZoneKind { RIVER_REACH, LAKE_BASIN, COASTAL_SEA }

enum class TraversalMode {
    LAND,
    FORD,
    BRIDGE,
    FERRY,
    EMBARK,
    DISEMBARK,
    RIVER_UP,
    RIVER_DOWN,
    LAKE,
    COASTAL,

    ;

    internal fun hasSymmetricEndpoints(): Boolean = when (this) {
        LAND, FORD, BRIDGE, FERRY, LAKE, COASTAL -> true
        EMBARK, DISEMBARK, RIVER_UP, RIVER_DOWN -> false
    }
}

enum class SeasonalAvailability { ALWAYS, SEASONAL, CLOSED }

enum class EvidenceConfidence { EXACT, REVIEWED, INFERRED }

enum class DepthBand { SHALLOW, MEDIUM, DEEP }

enum class RiskBand { LOW, MEDIUM, HIGH }

sealed interface StrategicNodeRef {
    val canonicalKey: String

    data class LandProvince(val id: Int) : StrategicNodeRef {
        init {
            require(id >= 0) { "Land province id must be non-negative: $id" }
        }

        override val canonicalKey: String = "land:$id"
    }

    data class WaterZone(val id: String) : StrategicNodeRef {
        init {
            require(id.isNotBlank()) { "Water zone id must not be blank" }
        }

        override val canonicalKey: String = "water:$id"
    }
}

data class WaterZoneRecord(
    val id: String,
    val kind: WaterZoneKind,
    val geometryRef: String,
    val sourceRefs: List<String>,
    val confidence: EvidenceConfidence,
    val flowDirection: String? = null,
    val depthBand: DepthBand? = null,
    val seasonalAvailability: SeasonalAvailability,
) {
    init {
        require(id.isNotBlank()) { "Water zone id must not be blank" }
        require(geometryRef.isNotBlank()) { "Water zone $id requires geometry evidence" }
        require(sourceRefs.isNotEmpty() && sourceRefs.none(String::isBlank)) {
            "Water zone $id requires source references"
        }
    }
}

data class TraversalEdge(
    val id: String,
    val from: StrategicNodeRef,
    val to: StrategicNodeRef,
    val mode: TraversalMode,
    val directed: Boolean,
    val movementCost: Int,
    val capacity: Int,
    val riskBand: RiskBand,
    val seasonalAvailability: SeasonalAvailability,
    val supplyAllowed: Boolean = false,
    val sourceRefs: List<String>,
    val confidence: EvidenceConfidence,
) {
    init {
        require(id.isNotBlank()) { "Traversal edge id must not be blank" }
        require(movementCost > 0) { "Traversal edge $id movementCost must be positive" }
        require(capacity > 0) { "Traversal edge $id capacity must be positive" }
        require(sourceRefs.isNotEmpty() && sourceRefs.none(String::isBlank)) {
            "Traversal edge $id requires source references"
        }
    }

    internal fun canonicalEdgeKey(): String {
        val endpoints = if (!mode.hasSymmetricEndpoints()) {
            "${from.canonicalKey}>${to.canonicalKey}"
        } else {
            listOf(from.canonicalKey, to.canonicalKey).sorted().joinToString("<>")
        }
        return "${mode.name}:$endpoints"
    }
}

data class RiverBarrier(
    val id: String,
    val firstLandProvinceId: Int,
    val secondLandProvinceId: Int,
    val sourceRefs: List<String>,
    val confidence: EvidenceConfidence,
) {
    init {
        require(id.isNotBlank()) { "River barrier id must not be blank" }
        require(firstLandProvinceId != secondLandProvinceId) { "River barrier $id cannot be a self boundary" }
        require(sourceRefs.isNotEmpty() && sourceRefs.none(String::isBlank)) {
            "River barrier $id requires source references"
        }
    }

    val canonicalBoundaryKey: String =
        listOf(firstLandProvinceId, secondLandProvinceId).sorted().joinToString(":")
}

class StrategicTopologySnapshot(
    topologyRevision: String,
    landProvinceIds: Set<Int>,
    waterZones: List<WaterZoneRecord>,
    traversalEdges: List<TraversalEdge>,
    riverBarriers: List<RiverBarrier>,
    artifactHashes: Map<String, String>,
) {
    val topologyRevision: String = topologyRevision
    val landProvinceIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(landProvinceIds))
    val waterZones: List<WaterZoneRecord> = Collections.unmodifiableList(
        waterZones.map { it.copy(sourceRefs = immutableList(it.sourceRefs)) },
    )
    val traversalEdges: List<TraversalEdge> = Collections.unmodifiableList(
        traversalEdges.map { it.copy(sourceRefs = immutableList(it.sourceRefs)) },
    )
    val riverBarriers: List<RiverBarrier> = Collections.unmodifiableList(
        riverBarriers.map { it.copy(sourceRefs = immutableList(it.sourceRefs)) },
    )
    val artifactHashes: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(artifactHashes))

    init {
        require(topologyRevision.isNotBlank()) { "Topology revision must not be blank" }
        require(landProvinceIds.all { it >= 0 }) { "Land province ids must be non-negative" }
        require(artifactHashes.isNotEmpty() && artifactHashes.all { it.key.isNotBlank() && it.value.isNotBlank() }) {
            "Topology must pin at least one named artifact hash"
        }
        requireUnique("water zone", waterZones.map(WaterZoneRecord::id))
        requireUnique("traversal edge", traversalEdges.map(TraversalEdge::id))
        requireUnique("river barrier", riverBarriers.map(RiverBarrier::id))
        requireUnique("canonical traversal edge", traversalEdges.map(TraversalEdge::canonicalEdgeKey))
        requireUnique("river boundary", riverBarriers.map(RiverBarrier::canonicalBoundaryKey))

        val waterIds = waterZones.mapTo(hashSetOf(), WaterZoneRecord::id)
        val waterById = waterZones.associateBy(WaterZoneRecord::id)
        traversalEdges.forEach { edge ->
            require(edge.from != edge.to) { "Traversal edge ${edge.id} cannot be a self edge" }
            require(nodeExists(edge.from, waterIds)) { "Traversal edge ${edge.id} has dangling from ${edge.from.canonicalKey}" }
            require(nodeExists(edge.to, waterIds)) { "Traversal edge ${edge.id} has dangling to ${edge.to.canonicalKey}" }
            validateEndpointKinds(edge, waterById)
            require(edge.mode == TraversalMode.LAND || edge.confidence != EvidenceConfidence.INFERRED) {
                "Executable crossing/water traversal must be evidence-reviewed: ${edge.id}"
            }
            if (edge.mode == TraversalMode.RIVER_UP || edge.mode == TraversalMode.RIVER_DOWN) {
                require(edge.directed) { "River-flow traversal must declare directed=true: ${edge.id}" }
            }
            if (edge.directed) {
                require(edge.mode == TraversalMode.RIVER_UP || edge.mode == TraversalMode.RIVER_DOWN) {
                    "Only river-flow traversal may be directed: ${edge.id}"
                }
                require(edge.confidence != EvidenceConfidence.INFERRED) {
                    "Directed river traversal must be evidence-reviewed: ${edge.id}"
                }
            }
        }
        riverBarriers.forEach { barrier ->
            require(barrier.firstLandProvinceId in landProvinceIds && barrier.secondLandProvinceId in landProvinceIds) {
                "River barrier ${barrier.id} references an unknown land province"
            }
        }
    }

    val contentHash: String by lazy { sha256(canonicalHashInput()) }

    fun containsNode(node: StrategicNodeRef): Boolean = when (node) {
        is StrategicNodeRef.LandProvince -> node.id in landProvinceIds
        is StrategicNodeRef.WaterZone -> waterZones.any { it.id == node.id }
    }

    fun canonicalHashInput(): String = CanonicalEncoding().apply {
        token("revision")
        token(topologyRevision)
        token("land")
        strings(landProvinceIds.sorted().map(Int::toString))
        artifactHashes.toSortedMap().forEach { (name, hash) ->
            token("artifact")
            token(name)
            token(hash)
        }
        waterZones.sortedBy(WaterZoneRecord::id).forEach { zone ->
            token("water")
            token(zone.id)
            token(zone.kind.name)
            token(zone.geometryRef)
            strings(zone.sourceRefs.sorted())
            token(zone.confidence.name)
            token(zone.flowDirection.orEmpty())
            token(zone.depthBand?.name.orEmpty())
            token(zone.seasonalAvailability.name)
        }
        riverBarriers.sortedBy(RiverBarrier::id).forEach { barrier ->
            token("barrier")
            token(barrier.id)
            token(barrier.canonicalBoundaryKey)
            strings(barrier.sourceRefs.sorted())
            token(barrier.confidence.name)
        }
        traversalEdges.sortedBy(TraversalEdge::id).forEach { edge ->
            val endpoints = if (edge.mode.hasSymmetricEndpoints()) {
                listOf(edge.from.canonicalKey, edge.to.canonicalKey).sorted()
            } else {
                listOf(edge.from.canonicalKey, edge.to.canonicalKey)
            }
            token("edge")
            token(edge.id)
            strings(endpoints)
            token(edge.mode.name)
            token(edge.directed.toString())
            token(edge.movementCost.toString())
            token(edge.capacity.toString())
            token(edge.riskBand.name)
            token(edge.seasonalAvailability.name)
            token(edge.supplyAllowed.toString())
            strings(edge.sourceRefs.sorted())
            token(edge.confidence.name)
        }
    }.toString()

    private fun nodeExists(node: StrategicNodeRef, waterIds: Set<String>): Boolean = when (node) {
        is StrategicNodeRef.LandProvince -> node.id in landProvinceIds
        is StrategicNodeRef.WaterZone -> node.id in waterIds
    }

    private fun validateEndpointKinds(edge: TraversalEdge, waterById: Map<String, WaterZoneRecord>) {
        val fromLand = edge.from is StrategicNodeRef.LandProvince
        val toLand = edge.to is StrategicNodeRef.LandProvince
        val valid = when (edge.mode) {
            TraversalMode.LAND, TraversalMode.FORD, TraversalMode.BRIDGE, TraversalMode.FERRY -> fromLand && toLand
            TraversalMode.EMBARK -> fromLand && !toLand
            TraversalMode.DISEMBARK -> !fromLand && toLand
            TraversalMode.RIVER_UP, TraversalMode.RIVER_DOWN, TraversalMode.LAKE, TraversalMode.COASTAL -> !fromLand && !toLand
        }
        require(valid) { "Traversal edge ${edge.id} has endpoints incompatible with ${edge.mode}" }

        val fromWater = (edge.from as? StrategicNodeRef.WaterZone)?.id?.let(waterById::get)
        val toWater = (edge.to as? StrategicNodeRef.WaterZone)?.id?.let(waterById::get)
        val expectedKind = when (edge.mode) {
            TraversalMode.RIVER_UP, TraversalMode.RIVER_DOWN -> WaterZoneKind.RIVER_REACH
            TraversalMode.LAKE -> WaterZoneKind.LAKE_BASIN
            TraversalMode.COASTAL -> WaterZoneKind.COASTAL_SEA
            else -> null
        }
        if (expectedKind != null) {
            require(fromWater?.kind == expectedKind && toWater?.kind == expectedKind) {
                "Traversal edge ${edge.id} mode ${edge.mode} requires $expectedKind endpoints"
            }
        }
    }

    private fun requireUnique(label: String, values: List<String>) {
        require(values.size == values.toSet().size) { "Duplicate $label id/key" }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private class CanonicalEncoding {
        private val value = StringBuilder()

        fun token(token: String) {
            val byteLength = token.toByteArray(StandardCharsets.UTF_8).size
            value.append(byteLength).append(':').append(token)
        }

        fun strings(tokens: List<String>) {
            token(tokens.size.toString())
            tokens.forEach(::token)
        }

        override fun toString(): String = value.toString()
    }
}
