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
    val landMarchMetrics: opensamguk.logic.world.LandMarchMetricSnapshot by lazy {
        HanLandMarchMetricJson.load(projection.topology,
            artifactBytes(opensamguk.logic.world.LandMarchMetricSnapshot.TILES_PATH))
    }
    val provinceCells: opensamguk.logic.world.HanProvinceCellIndex by lazy {
        HanProvinceCellJson.load(projection.topology,
            artifactBytes(opensamguk.logic.world.LandMarchMetricSnapshot.TILES_PATH))
    }
    val cityConst get() = CityConstRegistry.hanWorld(variant)
    init {
        require(cityConst.all().keys == projection.bindingsByCityId.keys) { "Han runtime constants and topology roster differ" }
    }
    fun artifactBytes(path: String): ByteArray = artifacts.getValue(path).copyOf()
}

/** Cache immutable artifacts, never the world's selection: a reset can change its roster. */
class HanWorldArtifactsResolver(private val root: Path = defaultRoot()) {
    companion object {
        /**
         * 엔진·API 는 저장소 루트에서 뜨므로 기본값은 `.` 이다. 그 전제가 성립하지 않는 곳 —
         * Gradle 이 모듈 디렉터리(app/game-engine)에서 띄우는 통합 테스트처럼 — 에서는 이
         * 시스템 프로퍼티나 환경 변수로 루트를 가리킨다. 설정하지 않으면 동작이 그대로다.
         */
        fun defaultRoot(): Path =
            (System.getProperty("opensamguk.artifacts.root")
                ?: System.getenv("OPENSAMGUK_ARTIFACTS_ROOT"))
                ?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(".")
    }

    private val cache = ConcurrentHashMap<HanWorldVariant, ResolvedHanWorldArtifacts>()

    fun artifacts(variant: HanWorldVariant): ResolvedHanWorldArtifacts = cache.computeIfAbsent(variant) {
        if (it == HanWorldVariant.V3_846) Han846Artifacts.load(root)
        else if (it == HanWorldVariant.V3_848) Han848Artifacts.load(root)
        else if (it == HanWorldVariant.V3_1098) Han1098Artifacts.load(root)
        else if (it == HanWorldVariant.V3_1168) Han1168Artifacts.load(root)
        else if (it == HanWorldVariant.V3_1194) Han1194Artifacts.load(root)
        else if (it == HanWorldVariant.V3_1341) Han1341Artifacts.load(root)
        else if (it == HanWorldVariant.V3_1141) Han1141Artifacts.load(root)
        else if (it == HanWorldVariant.V3_1133) Han1133Artifacts.load(root)
        else HanHistoricalArtifacts.loadBundleFromDirectory(root, it.artifactId)
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
