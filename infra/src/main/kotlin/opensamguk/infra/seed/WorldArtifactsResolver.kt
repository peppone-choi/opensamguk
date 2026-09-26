package opensamguk.infra.seed

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.WorldMapVariant
import opensamguk.logic.world.StrategicRouteProjection

/** A stored pin from one of the three world-scoped spatial tables. */
data class WorldTopologyPin(val channel: String, val revision: String?, val hash: String?)

class ResolvedWorldArtifacts internal constructor(
    val variant: WorldMapVariant,
    val projection: StrategicRouteProjection,
    bytes: Map<String, ByteArray>,
) {
    private val artifacts = bytes.mapValues { it.value.copyOf() }
    val landMarchMetrics: opensamguk.logic.world.LandMarchMetricSnapshot by lazy {
        LandMarchMetricJson.load(projection.topology,
            artifactBytes(opensamguk.logic.world.LandMarchMetricSnapshot.TILES_PATH))
    }
    val provinceCells: opensamguk.logic.world.ProvinceCellIndex by lazy {
        ProvinceCellJson.load(projection.topology,
            artifactBytes(opensamguk.logic.world.LandMarchMetricSnapshot.TILES_PATH))
    }
    /** 郡國 numbering and shared-border graph for HWIHA vision (same numbers as the provinces PNG). */
    val commanderyIndex: opensamguk.logic.world.CommanderyIndex by lazy {
        CommanderyIndexJson.load(projection.topology,
            artifactBytes(opensamguk.logic.world.LandMarchMetricSnapshot.TILES_PATH))
    }
    val cityConst get() = CityConstRegistry.hanWorld(variant)
    init {
        require(cityConst.all().keys == projection.bindingsByCityId.keys) { "Han runtime constants and topology roster differ" }
    }
    fun artifactBytes(path: String): ByteArray = artifacts.getValue(path).copyOf()
}

/** Cache immutable artifacts, never the world's selection: a reset can change its roster. */
class WorldArtifactsResolver(private val root: Path = defaultRoot()) {
    companion object {
        // Immutable topology pins for the two releases with the same 1447-city roster.
        // Select from these before loading either multi-megabyte bundle.
        private const val V3_1447_HASH = "393e42c8b0ff59b03f3bf5a1c67f41eb12caa53ce71033097480918f977b7ecb"
        private const val V3_1447_MAP4_HASH = "eaf06460f978cbfb16a08cbaa65edf6ba71bc82cd12426a7a823baaba847db14"
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

    private val cache = ConcurrentHashMap<WorldMapVariant, ResolvedWorldArtifacts>()

    fun artifacts(variant: WorldMapVariant): ResolvedWorldArtifacts = cache.computeIfAbsent(variant) {
        if (it == WorldMapVariant.V3_846) Archive846Artifacts.load(root)
        else if (it == WorldMapVariant.V3_848) Archive848Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1098) Archive1098Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1168) Archive1168Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1224) Archive1224Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1447) Archive1447Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1447_MAP4) Archive1447Map4Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1194) Archive1194Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1341) Archive1341Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1141) Archive1141Artifacts.load(root)
        else if (it == WorldMapVariant.V3_1133) Archive1133Artifacts.load(root)
        else HistoricalArtifacts.loadBundleFromDirectory(root, it.artifactId)
    }

    /** The caller must provide the complete world roster and all world-scoped persisted pins. */
    fun resolve(completeCityIds: Collection<Int>, pins: Collection<WorldTopologyPin>): ResolvedWorldArtifacts {
        val ids = completeCityIds.toSet()
        require(ids.size == completeCityIds.size) { "Duplicate world city identities" }
        val candidates = WorldMapVariant.entries.filter { CityConstRegistry.hanWorld(it).all().keys == ids }
        require(candidates.isNotEmpty()) { "World city identities do not select a registered Han artifact set" }
        val selected = if (candidates.size == 1) {
            artifacts(candidates.single())
        } else {
            // Both 1447 releases have the same city identities. Stored topology
            // pins identify their grid; unpinned old worlds keep the old release.
            if (pins.isEmpty()) artifacts(WorldMapVariant.V3_1447)
            else {
                require(pins.all { it.revision == "han-water-topology-v1" && it.hash == pins.first().hash }) {
                    "World spatial pins disagree"
                }
                val variant = when (pins.first().hash) {
                    V3_1447_HASH -> WorldMapVariant.V3_1447
                    V3_1447_MAP4_HASH -> WorldMapVariant.V3_1447_MAP4
                    else -> throw IllegalArgumentException("World spatial pins do not select one 1447 release")
                }
                artifacts(variant)
            }
        }
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
