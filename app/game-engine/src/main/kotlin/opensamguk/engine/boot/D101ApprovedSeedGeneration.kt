package opensamguk.engine.boot

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.sql.DataSource

/**
 * Raw approval-intent/typed-target linkage consumed by C4. The installer must
 * first authenticate the original with C8's independent approval authority.
 */
class D101ApprovedSeedGeneration(approvalIntentOriginal: ByteArray, expectedApprovalIntentSha256: String) {
    val approvalIntentSha256: String
    val originalOp: String
    val typedTargetFingerprint: String
    val appSourceSha: String
    val selectedSourceReceiptSha256: String
    val generation: Int
    val imagePins: Map<String, String>
    val options: Map<String, String>

    init {
        val wire = approvalIntentOriginal.copyOf()
        if (wire.isEmpty() || wire.size > 32 * 1024 || !SHA.matches(expectedApprovalIntentSha256) ||
            sha(wire) != expectedApprovalIntentSha256) throw SelectedSourceUnavailable()
        val intent = strictJson(wire)
        if (!intent.isObject) throw SelectedSourceUnavailable()
        val targetSha = string(intent, "targetFingerprint")
        if (!SHA.matches(targetSha)) throw SelectedSourceUnavailable()
        val targetBytes = try {
            Base64.getUrlDecoder().decode(string(intent, "rootTargetBytesBase64url"))
        } catch (_: IllegalArgumentException) { throw SelectedSourceUnavailable() }
        if (targetBytes.isEmpty() || targetBytes.size > 16 * 1024 || sha(targetBytes) != targetSha) {
            throw SelectedSourceUnavailable()
        }
        val wrapper = strictJson(targetBytes)
        if (!wrapper.isObject || string(wrapper, "id") != "pep") throw SelectedSourceUnavailable()
        val target = wrapper["target"] ?: throw SelectedSourceUnavailable()
        if (!target.isObject || string(target, "scenarioCode") != "scenario_3190" ||
            target["scenarioSeedEnabled"]?.isBoolean != true || !target["scenarioSeedEnabled"].booleanValue()) {
            throw SelectedSourceUnavailable()
        }
        val number = target["generation"] ?: throw SelectedSourceUnavailable()
        if (!number.isIntegralNumber || !number.canConvertToInt() || number.intValue() != 0) {
            throw SelectedSourceUnavailable()
        }
        val updates = target["updates"] ?: throw SelectedSourceUnavailable()
        if (!updates.isObject || updates.fieldNames().asSequence().toSet().containsAll(OPTION_KEYS).not()) {
            throw SelectedSourceUnavailable()
        }
        val parsedOptions = OPTION_KEYS.associateWith { string(updates, it) }
        if (parsedOptions["SERVER_GENERATION"] != number.intValue().toString() ||
            parsedOptions["SERVER_NAME"] != "빼섭" ||
            parsedOptions["SCENARIO_CODE"] != "scenario_3190" ||
            parsedOptions["SCENARIO_SEED_ENABLED"] != "true") throw SelectedSourceUnavailable()
        val appPins = pins(target["imageDigests"], APP_IMAGES)
        val storagePins = pins(target["storageImageDigests"], STORAGE_IMAGES)
        val allPins = appPins + storagePins
        if (pins(intent["newImageDigests"], ALL_IMAGES) != allPins) throw SelectedSourceUnavailable()
        val op = string(intent, "operationId")
        val app = string(intent, "appSourceSha")
        val selectedReceipt = string(intent, "selectedSourceReceiptSha256")
        val worldId = intent["worldId"]
        if (!Regex("[0-9a-f]{32}").matches(op) || !Regex("[0-9a-f]{40}").matches(app) ||
            !SHA.matches(selectedReceipt) ||
            string(intent, "serverId") != "pep" || string(intent, "serverName") != "빼섭" ||
            worldId == null || !worldId.isIntegralNumber || worldId.bigIntegerValue() != java.math.BigInteger.ONE) {
            throw SelectedSourceUnavailable()
        }
        approvalIntentSha256 = expectedApprovalIntentSha256
        originalOp = op
        typedTargetFingerprint = targetSha
        appSourceSha = app
        selectedSourceReceiptSha256 = selectedReceipt
        generation = number.intValue()
        imagePins = allPins.toSortedMap()
        options = parsedOptions.toSortedMap()
    }

    internal companion object {
        val SHA = Regex("[0-9a-f]{64}")
        val APP_IMAGES = setOf("game-api", "game-engine", "web-game")
        val STORAGE_IMAGES = setOf("game-postgres", "game-redis")
        val ALL_IMAGES = APP_IMAGES + STORAGE_IMAGES
        val OPTION_KEYS = setOf("SCENARIO_CODE", "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR",
            "RESET_MAXGENERAL", "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM",
            "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL", "RESET_FICTION",
            "SERVER_NAME", "SERVER_GENERATION")
        val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        fun strictJson(bytes: ByteArray): JsonNode = try {
            mapper.readTree(bytes) ?: throw SelectedSourceUnavailable()
        } catch (_: Exception) { throw SelectedSourceUnavailable() }
        fun string(node: JsonNode, key: String): String =
            node[key]?.takeIf { it.isTextual }?.textValue() ?: throw SelectedSourceUnavailable()
        fun pins(node: JsonNode?, keys: Set<String>): Map<String, String> {
            if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != keys) {
                throw SelectedSourceUnavailable()
            }
            return keys.associateWith { key ->
                string(node, key).also { if (!it.matches(Regex("sha256:[0-9a-f]{64}"))) throw SelectedSourceUnavailable() }
            }
        }
        fun sha(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** Called only by ScenarioSeedCoordinator after import, on its transaction-bound JdbcTemplate. */
internal object D101ApprovedGenerationWriter {
    fun persist(jdbc: JdbcTemplate, approval: D101ApprovedSeedGeneration) {
        if (approval.generation != 0) throw SelectedSourceUnavailable()
        val changed = jdbc.update(
            """
            UPDATE world_state
               SET meta = jsonb_set(
                   jsonb_set(meta, ARRAY['server_generation'], to_jsonb(CAST(? AS integer)), true),
                   ARRAY['server_generation_approval_intent_sha256'], to_jsonb(CAST(? AS text)), true)
             WHERE id = 1
               AND meta -> 'server_generation' IS NULL
               AND meta -> 'server_generation_approval_intent_sha256' IS NULL
            """.trimIndent(),
            approval.generation, approval.approvalIntentSha256,
        )
        if (changed != 1) throw SelectedSourceUnavailable()
    }

    /** Postcommit readback of the exact DB meta original also seen by the C8 cap gate. */
    fun readback(source: DataSource, expectedMetaSha256: String, approval: D101ApprovedSeedGeneration): Int {
        if (!Regex("[0-9a-f]{64}").matches(expectedMetaSha256)) throw SelectedSourceUnavailable()
        val raw = JdbcTemplate(source).queryForObject(
            "SELECT meta FROM world_state WHERE id = 1", String::class.java,
        )?.toByteArray(Charsets.UTF_8) ?: throw SelectedSourceUnavailable()
        if (raw.isEmpty() || raw.size > 64 * 1024 ||
            D101ApprovedSeedGeneration.sha(raw) != expectedMetaSha256) {
            throw SelectedSourceUnavailable()
        }
        val meta = D101ApprovedSeedGeneration.strictJson(raw)
        val generation = meta["server_generation"] ?: throw SelectedSourceUnavailable()
        if (!meta.isObject || !generation.isIntegralNumber || !generation.canConvertToInt() ||
            generation.intValue() != approval.generation ||
            D101ApprovedSeedGeneration.string(meta, "server_generation_approval_intent_sha256") !=
                approval.approvalIntentSha256) {
            throw SelectedSourceUnavailable()
        }
        return generation.intValue()
    }
}
