package opensamguk.infra.seed

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.HanWorldVariant
import opensamguk.logic.world.HanStrategicRouteProjection

/** A stored pin from one of the three world-scoped spatial tables. */
data class HanWorldTopologyPin(val channel: String, val revision: String?, val hash: String?)

class ResolvedHanWorldArtifacts internal constructor(
    val variant: HanWorldVariant,
    val projection: HanStrategicRouteProjection,
    bytes: Map<String, ByteArray>,
) {
    private val artifacts = bytes.mapValues { it.value.copyOf() }
    val cityConst get() = CityConstRegistry.hanWorld(variant)
    init {
        require(cityConst.all().keys == projection.bindingsByCityId.keys) { "Han runtime constants and topology roster differ" }
    }
    fun artifactBytes(path: String): ByteArray = artifacts.getValue(path).copyOf()
}

/** Cache immutable artifacts, never the world's selection: a reset can change its roster. */
class HanWorldArtifactsResolver(private val root: Path = Path.of(".")) {
    private val cache = ConcurrentHashMap<HanWorldVariant, ResolvedHanWorldArtifacts>()

    fun artifacts(variant: HanWorldVariant): ResolvedHanWorldArtifacts = cache.computeIfAbsent(variant) {
        HanHistoricalArtifacts.loadBundleFromDirectory(root, it.artifactId)
    }

    /** The caller must provide the complete world roster and all world-scoped persisted pins. */
    fun resolve(completeCityIds: Collection<Int>, pins: Collection<HanWorldTopologyPin>): ResolvedHanWorldArtifacts {
        val ids = completeCityIds.toSet()
        require(ids.size == completeCityIds.size) { "Duplicate world city identities" }
        val candidates = HanWorldVariant.entries.filter { CityConstRegistry.hanWorld(it).all().keys == ids }
        require(candidates.size == 1) { "World city identities do not select a unique registered Han artifact set" }
        val selected = artifacts(candidates.single())
        val topology = selected.projection.topology
        pins.forEach { pin ->
            require(pin.channel in setOf("water_zone_control", "province_control", "general_spatial_position")) {
                "Unknown spatial pin channel"
            }
            require(pin.revision == topology.topologyRevision && pin.hash == topology.contentHash) {
                "World spatial pin disagrees with ${selected.variant.artifactId}: ${pin.channel}"
            }
        }
        return selected
    }
}
