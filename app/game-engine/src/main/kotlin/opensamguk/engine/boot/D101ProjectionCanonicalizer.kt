package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/** Serializes the C8 D101_C4_MEMBERSHIP_CLOCK_V1 field profile; custody is checked elsewhere. */
class D101ProjectionCanonicalizer {
    data class SourcePins(
        val appSourceSha: String,
        val scenarioRawBytesSha256: String,
        val mapRawBytesSha256: String,
        val topologyHash: String,
    ) {
        init {
            require(appSourceSha.matches(Regex("[0-9a-f]{40}"))) { "app source pin is unavailable" }
            for (pin in listOf(scenarioRawBytesSha256, mapRawBytesSha256, topologyHash)) {
                require(pin.matches(Regex("[0-9a-f]{64}"))) { "selected source pin is unavailable" }
            }
        }
    }

    private val mapper = ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun canonicalBytes(snapshot: D101ProjectionSnapshotReader.Snapshot, pins: SourcePins): ByteArray {
        requireKeys(snapshot.world, setOf("worldId", "generation", "scenarioCode", "currentYear",
            "currentMonth", "currentPhase", "tickSeconds", "lastTurnOffsetNanos", "nextBoundaryOffsetNanos"))
        requireKeys(snapshot.seedSettings, setOf("maxGeneralConfig", "maxGeneralGameEnv",
            "blockGeneralCreateConfig", "blockGeneralCreateGameEnv", "firstTurn", "extendedGeneral"))
        validateRows(snapshot.generals, 6, emptySet())
        validateRows(snapshot.nations, 4, setOf(2))
        validateRows(snapshot.cities, 3, emptySet())
        validateRows(snapshot.positions, 6, emptySet())
        validateRows(snapshot.retainers, 11, setOf(2))
        val source = sortedMapOf<String, Any>(
            "appSourceSha" to pins.appSourceSha,
            "scenarioRawBytesSha256" to pins.scenarioRawBytesSha256,
            "mapRawBytesSha256" to pins.mapRawBytesSha256,
            "topologyHash" to pins.topologyHash,
        )
        val root = sortedMapOf<String, Any>(
            "schemaVersion" to 1,
            "projectionKind" to "D101_C4_MEMBERSHIP_CLOCK_V1",
            "source" to source,
            "world" to TreeMap(snapshot.world),
            "seedSettings" to TreeMap(snapshot.seedSettings),
            "generals" to snapshot.generals,
            "nations" to snapshot.nations,
            "cities" to snapshot.cities,
            "positions" to snapshot.positions,
            "retainers" to snapshot.retainers,
        )
        val json = mapper.writeValueAsString(root)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return (json + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private fun requireKeys(value: Map<String, Any>, keys: Set<String>) {
        require(value.keys == keys) { "projection field set differs from C8 profile v1" }
    }

    private fun validateRows(rows: List<List<Any?>>, width: Int, nullable: Set<Int>) {
        var lastId = Long.MIN_VALUE
        for (row in rows) {
            require(row.size == width) { "projection row width differs from C8 profile v1" }
            for ((index, cell) in row.withIndex()) {
                require(cell != null || index in nullable) { "non-null projection column is missing" }
                require(cell !is Float && cell !is Double) { "floating-point projection value is forbidden" }
            }
            val id = row[0] as? Number ?: error("projection logical ID is unavailable")
            val currentId = id.toLong()
            require(currentId > lastId) { "projection rows are not ordered by unique logical ID" }
            lastId = currentId
        }
    }
}
