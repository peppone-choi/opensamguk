package opensamguk.gateway.d101.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.service.ServerDef
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Original private producer snapshots, authenticated inside the distinct signed
 * recovery result. No endpoint/name reconstruction and no PUBLIC restoration. */
internal class D101RecoverySnapshots private constructor(
    val oldCanonicalRegistry: ServerDef,
    val restoredWorld: D101RestoredWorldObservation,
    registryOriginal: ByteArray,
    worldOriginal: ByteArray,
) {
    private val registry = registryOriginal.copyOf()
    private val world = worldOriginal.copyOf()
    fun oldRegistryOriginalBytes() = registry.copyOf()
    fun oldWorldOriginalBytes() = world.copyOf()

    companion object {
        private val canonicalKeys = setOf("id","name","gameApiUrl","gameEngineUrl","deployProject","generation","scenarioCode")
        private val canonicalMapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        /** The original seven-field registry permits null only for its two historical optional fields. */
        internal fun decodeCanonicalRegistry(json: D101StrictJson, original: ByteArray,
            oldGeneration: Int, oldScenarioCode: String): ServerDef {
            fun reject(): Nothing = throw D101ObservationUnavailable()
            if (original.isEmpty() || original.size > 16 * 1024 ||
                original.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) ||
                oldGeneration < 0 || !oldScenarioCode.matches(Regex("scenario_[0-9]+"))) reject()
            val r = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original))
                canonicalMapper.readTree(original)
            } catch (_: Exception) { reject() }
            if (r == null || !r.isObject || r.fieldNames().asSequence().toSet() != canonicalKeys) reject()
            val generation = when (val node = r["generation"]) {
                null -> reject()
                else -> when {
                    node.isNull -> null
                    node.isIntegralNumber && node.canConvertToInt() && node.intValue() >= 0 -> node.intValue()
                    else -> reject()
                }
            }
            val scenario = when (val node = r["scenarioCode"]) {
                null -> reject()
                else -> when {
                    node.isNull -> null
                    node.isTextual && node.textValue().matches(Regex("scenario_[0-9]+")) -> node.textValue()
                    else -> reject()
                }
            }
            val canonical = ServerDef(json.text(r["id"]), json.text(r["name"]), json.text(r["gameApiUrl"]),
                json.text(r["gameEngineUrl"]), json.text(r["deployProject"]), generation, scenario)
            if (canonical.id != "pep" || canonical.name.isBlank() ||
                canonical.gameApiUrl != "http://spep-game-api:8081" ||
                canonical.gameEngineUrl != "http://spep-game-engine:8082" || canonical.deployProject != "opensamguk-spep" ||
                (generation != null && generation != oldGeneration) ||
                (scenario != null && scenario != oldScenarioCode)) reject()
            return canonical
        }

        fun decode(json: D101StrictJson, e: D101Execution, beginSha: String,
            registryOriginal: ByteArray, registrySha: String, worldOriginal: ByteArray, worldSha: String,
            oldGeneration: Int, oldScenarioCode: String, databaseSha: String, runtimeSha: String,
            started: Instant, completed: Instant): D101RecoverySnapshots {
            fun reject(): Nothing = throw D101ObservationUnavailable()
            if (D101StrictJson.hash(registryOriginal) != registrySha || D101StrictJson.hash(worldOriginal) != worldSha) reject()
            val canonical = decodeCanonicalRegistry(json, registryOriginal, oldGeneration, oldScenarioCode)
            val w = json.objectBytes(worldOriginal, WORLD_KEYS, 16 * 1024)
            val worldGeneration = w["generation"]
            if (json.positiveLong(w["schemaVersion"]) != 1L || json.text(w["kind"]) != "D101_RESTORED_OLD_WORLD_V1" ||
                json.text(w["operationId"]) != e.intent.operationId || json.sha(w["approvalIntentSha256"]) != e.intent.sha256 ||
                json.sha(w["targetFingerprint"]) != e.intent.targetFingerprint ||
                json.revision(w["verifyingRevision"]) != e.verifyingRevision ||
                json.sha(w["recoveryBeginReceiptSha256"]) != beginSha || json.positiveLong(w["worldId"]) != 1L ||
                !worldGeneration.isIntegralNumber || !worldGeneration.canConvertToInt() || worldGeneration.intValue() != oldGeneration ||
                json.text(w["scenarioCode"]) != oldScenarioCode ||
                json.stringMap(w["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != e.intent.oldImageDigests ||
                json.sha(w["databaseReceiptSha256"]) != databaseSha || json.sha(w["runtimeReceiptSha256"]) != runtimeSha) reject()
            val tick = json.positiveLong(w["tickSeconds"])
            if (tick > Int.MAX_VALUE) reject()
            val timestamp = json.text(w["observedAtUtc"])
            if (!timestamp.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z"))) reject()
            val observed = Instant.parse(timestamp)
            if (observed < started || observed > completed) reject()
            return D101RecoverySnapshots(canonical,
                D101RestoredWorldObservation(1, oldGeneration, oldScenarioCode, tick.toInt(), observed,
                    databaseSha, runtimeSha, worldSha), registryOriginal, worldOriginal)
        }
        private val WORLD_KEYS = setOf("schemaVersion","kind","operationId","approvalIntentSha256","targetFingerprint",
            "verifyingRevision","recoveryBeginReceiptSha256","worldId","generation","scenarioCode","tickSeconds",
            "oldImageDigests","databaseReceiptSha256","runtimeReceiptSha256","observedAtUtc")
    }
}

internal data class D101RestoredWorldObservation(val worldId: Int, val generation: Int, val scenarioCode: String,
    val tickSeconds: Int, val observedAt: Instant, val databaseReceiptSha256: String,
    val runtimeReceiptSha256: String, val originalReceiptSha256: String)
