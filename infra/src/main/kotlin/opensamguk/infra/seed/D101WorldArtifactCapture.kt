package opensamguk.infra.seed

import opensamguk.logic.world.MapArtifactContract

/** Copies only the already selected immutable bundle. No resolver or path IO. */
class D101WorldArtifactCapture private constructor(
    val artifactSetId: String,
    val variant: String,
    val topologyRevision: String,
    val topologyContentHash: String,
    originals: Map<String, ByteArray>,
    topologyInputs: Map<String, ByteArray>,
    canonicalTopology: ByteArray,
) {
    private val originals = originals.mapValues { it.value.copyOf() }
    private val inputs = topologyInputs.mapValues { it.value.copyOf() }
    private val canonical = canonicalTopology.copyOf()
    fun mapOriginals(): Map<String, ByteArray> = originals.mapValues { it.value.copyOf() }
    fun topologyOriginals(): Map<String, ByteArray> = inputs.mapValues { it.value.copyOf() }
    fun canonicalTopologyBytes(): ByteArray = canonical.copyOf()

    companion object {
        private const val WORLD = "infra/src/main/resources/map/han-world-v3.json"
        private const val ROADS = "data/map/han-land-roads-v1.json"

        fun capture(selected: ResolvedWorldArtifacts): D101WorldArtifactCapture = try {
            val topology = selected.projection.topology
            val terrain = MapArtifactContract.entries.single { it.tilesPath in topology.artifactHashes }
            val inputs = topology.artifactHashes.mapValues { (path, expected) ->
                selected.artifactBytes(path).also {
                    if (it.isEmpty() || selectedOriginalSha(it) != expected) throw SelectedSourceUnavailable()
                }
            }
            val canonical = topology.canonicalHashInput().toByteArray(Charsets.UTF_8)
            if (selectedOriginalSha(canonical) != topology.contentHash) throw SelectedSourceUnavailable()
            val map = mapOf(
                "tiles.json" to inputs.getValue(terrain.tilesPath),
                "world.json" to inputs.getValue(WORLD),
                // Roads are a separate bundle input; their raw hash is not topology.contentHash.
                "roads.json" to selected.artifactBytes(ROADS),
            )
            if (map.values.any { it.isEmpty() }) throw SelectedSourceUnavailable()
            D101WorldArtifactCapture(selected.variant.artifactId, selected.variant.name,
                topology.topologyRevision, topology.contentHash, map, inputs, canonical)
        } catch (_: Exception) {
            throw SelectedSourceUnavailable()
        }
    }
}
