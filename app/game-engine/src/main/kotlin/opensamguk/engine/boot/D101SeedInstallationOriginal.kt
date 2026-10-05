package opensamguk.engine.boot

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.D101SeedOnlyFixedIdentity
import opensamguk.infra.seed.SelectedSourceUnavailable
import opensamguk.logic.world.StrategicTopologySnapshot
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/** Fixed child transport. C8's separate Root pins and signed verifiers supply authority. */
internal class D101SeedInstallationOriginal private constructor(
    val originalOp: String,
    val approvalIntentSha256: String,
    val typedTargetFingerprint: String,
    val appSourceSha: String,
    val imagePins: Map<String, String>,
    val producerIdentity: String,
    approvedMaterial: ByteArray,
    selectedEnvelope: ByteArray,
    configurationOriginal: ByteArray,
    resolverDecisionOriginal: ByteArray,
    typedTargetOriginal: ByteArray,
    parserBytecode: ByteArray,
    topologyRootBytecode: ByteArray,
    val actualOptions: Map<String, String>,
    val artifactsRoot: Path,
    val database: Database,
) {
    private val approval = approvedMaterial.copyOf()
    private val selected = selectedEnvelope.copyOf()
    private val configuration = configurationOriginal.copyOf()
    private val decision = resolverDecisionOriginal.copyOf()
    private val target = typedTargetOriginal.copyOf()
    private val parser = parserBytecode.copyOf()
    private val topology = topologyRootBytecode.copyOf()

    fun approvedMaterial() = approval.copyOf()
    fun selectedEnvelope() = selected.copyOf()
    fun configurationOriginal() = configuration.copyOf()
    fun resolverDecisionOriginal() = decision.copyOf()
    fun typedTargetOriginal() = target.copyOf()
    fun parserBytecode() = parser.copyOf()
    fun topologyRootBytecode() = topology.copyOf()

    data class Database(
        val host: String, val port: Int, val databaseName: String,
        val databaseUser: String, val passwordFile: Path,
    )

    companion object {
        private val directory = Path.of("/run/d101")
        private val installation = directory.resolve("installation.json")
        private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val shaPattern = Regex("[a-f0-9]{64}")
        private val imageNames = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        private val optionNames = setOf("SERVER_NAME", "SERVER_GENERATION", "SCENARIO_CODE",
            "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR", "RESET_MAXGENERAL",
            "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM", "RESET_BLOCK_GENERAL_CREATE",
            "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL", "RESET_FICTION")
        private val numericOptions = setOf("SERVER_GENERATION", "RESET_MAXGENERAL", "RESET_EXTEND",
            "RESET_TURNTERM", "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL",
            "RESET_FICTION")
        private val topKeys = setOf("schemaVersion", "kind", "originalOp", "approvalIntentSha256",
            "typedTargetFingerprint", "appSourceSha", "imagePins", "producerIdentity",
            "rootTrust", "approvedMaterial", "selectedEnvelope", "configurationOriginal",
            "resolverDecision", "typedTargetOriginal", "runtimeClasses", "artifactsRoot", "database")
        private val sourceNames = mapOf("approvedMaterial" to "seed-approval.json",
            "selectedEnvelope" to "selected-envelope.json",
            "configurationOriginal" to "configuration.json",
            "resolverDecision" to "resolver-decision.json",
            "typedTargetOriginal" to "typed-target.json")

        fun readFixed(identity: D101SeedOnlyFixedIdentity, loader: ClassLoader): D101SeedInstallationOriginal = try {
            val root = parseInstallationDocument(readFixedFile(installation, 64 * 1024))
            if (!root["schemaVersion"].isIntegralNumber ||
                root["schemaVersion"].bigIntegerValue() != java.math.BigInteger.ONE ||
                string(root, "kind") != "D101_SEED_INSTALLATION_V1") unavailable()
            val op = string(root, "originalOp")
            val intentSha = string(root, "approvalIntentSha256")
            val targetSha = string(root, "typedTargetFingerprint")
            val app = string(root, "appSourceSha")
            val producer = string(root, "producerIdentity")
            if (!op.matches(Regex("[a-f0-9]{32}")) || !shaPattern.matches(intentSha) ||
                !shaPattern.matches(targetSha) || !app.matches(Regex("[a-f0-9]{40}")) ||
                producer != identity.producerIdentity) unavailable()
            val pinsNode = root["imagePins"]
            requireKeys(pinsNode, imageNames)
            val pins = imageNames.associateWith { key -> string(pinsNode, key).also {
                if (!it.matches(Regex("sha256:[a-f0-9]{64}"))) unavailable()
            } }
            if (pins != identity.imagePins()) unavailable()
            val trust = root["rootTrust"]
            requireKeys(trust, setOf("keyId", "publicKeySpkiBase64url", "publicKeySpkiSha256"))
            if (string(trust, "keyId") != identity.producerIdentity ||
                string(trust, "publicKeySpkiSha256") != identity.publicKeySpkiSha256 ||
                string(trust, "publicKeySpkiBase64url") !=
                    Base64.getUrlEncoder().withoutPadding().encodeToString(identity.publicKeySpki())) unavailable()
            fun original(key: String, limit: Int): ByteArray {
                return readBoundOriginal(root[key], sourceNames[key] ?: unavailable(), limit, ::readFixedFile)
            }
            val approval = original("approvedMaterial", 128 * 1024)
            val selected = original("selectedEnvelope", 96 * 1024)
            val configuration = original("configurationOriginal", 64 * 1024)
            val decision = original("resolverDecision", 64 * 1024)
            val target = original("typedTargetOriginal", 16 * 1024)
            if (sha(target) != targetSha) unavailable()
            val config = json(configuration, setOf("schemaVersion", "kind", "originalOp",
                "typedTargetFingerprint", "appSourceSha", "imagePins", "rawInputs"))
            if (!config["schemaVersion"].isIntegralNumber ||
                config["schemaVersion"].bigIntegerValue() != java.math.BigInteger.ONE ||
                string(config, "kind") != "D101_EFFECTIVE_SEED_INPUTS_V1" ||
                string(config, "originalOp") != op ||
                string(config, "typedTargetFingerprint") != targetSha ||
                string(config, "appSourceSha") != app) unavailable()
            val configPins = config["imagePins"]
            requireKeys(configPins, imageNames)
            if (imageNames.associateWith { string(configPins, it) } != pins) unavailable()
            val raw = config["rawInputs"]
            requireKeys(raw, optionNames)
            val actualOptions = optionNames.associateWith { key ->
                val input = string(raw, key)
                if (key in numericOptions) {
                    val trimmed = input.trim()
                    if (!trimmed.matches(Regex("[0-9]+"))) unavailable()
                    trimmed.toIntOrNull()?.toString() ?: unavailable()
                } else input
            }
            val runtime = root["runtimeClasses"]
            requireKeys(runtime, setOf("parser", "topology"))
            fun bytecode(name: String, requiredBinaryName: String): ByteArray {
                val pin = runtime[name]
                requireKeys(pin, setOf("binaryName", "sha256"))
                if (string(pin, "binaryName") != requiredBinaryName) unavailable()
                val expected = string(pin, "sha256")
                if (!shaPattern.matches(expected)) unavailable()
                val resource = requiredBinaryName.replace('.', '/') + ".class"
                val bytes = loader.getResourceAsStream(resource)?.use { it.readNBytes(2 * 1024 * 1024 + 1) }
                    ?: unavailable()
                if (bytes.size !in 4..2 * 1024 * 1024 || sha(bytes) != expected ||
                    !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(),
                        0xBA.toByte(), 0xBE.toByte()))) unavailable()
                return bytes
            }
            if (loader.loadClass(SeedBootstrap::class.java.name) != SeedBootstrap::class.java ||
                loader.loadClass(StrategicTopologySnapshot::class.java.name) !=
                    StrategicTopologySnapshot::class.java) unavailable()
            val parser = bytecode("parser", SeedBootstrap::class.java.name)
            val topology = bytecode("topology", StrategicTopologySnapshot::class.java.name)
            val artifacts = Path.of(string(root, "artifactsRoot"))
            if (artifacts != Path.of("/app")) unavailable()
            val db = root["database"]
            requireKeys(db, setOf("host", "port", "databaseName", "databaseUser", "passwordFile"))
            val host = string(db, "host")
            val name = string(db, "databaseName")
            val user = string(db, "databaseUser")
            val port = db["port"]
            val passwordPath = childPath(string(db, "passwordFile"))
            if (passwordPath.fileName.toString() != "password") unavailable()
            if (!host.matches(Regex("[a-z0-9][a-z0-9.-]{0,252}")) ||
                !name.matches(Regex("[A-Za-z0-9_]{1,63}")) ||
                !user.matches(Regex("[A-Za-z0-9_]{1,63}")) ||
                !port.isIntegralNumber || !port.canConvertToInt() || port.intValue() !in 1..65535) unavailable()
            D101SeedInstallationOriginal(op, intentSha, targetSha, app, pins.toMap(), producer,
                approval, selected, configuration, decision, target, parser, topology,
                actualOptions.toMap(), artifacts, Database(host, port.intValue(), name, user,
                    passwordPath))
        } catch (_: Exception) { unavailable() }

        internal fun parseInstallationDocument(bytes: ByteArray): JsonNode = json(bytes, topKeys)

        /** The same Root-produced ref shape and original-byte check used during installation. */
        internal fun readBoundOriginal(
            ref: JsonNode?, requiredFileName: String, limit: Int,
            reader: (Path, Int) -> ByteArray,
        ): ByteArray {
            requireKeys(ref, setOf("filePath", "sha256"))
            val value = ref ?: unavailable()
            val expected = string(value, "sha256")
            if (!shaPattern.matches(expected)) unavailable()
            val path = childPath(string(value, "filePath"))
            if (path.fileName.toString() != requiredFileName) unavailable()
            return reader(path, limit).also {
                if (it.size !in 1..limit || sha(it) != expected) unavailable()
            }
        }

        internal fun readFixedFile(path: Path, limit: Int): ByteArray {
            if (path.parent != directory || path.toRealPath() != path || directory.toRealPath() != directory) unavailable()
            fun attributes(candidate: Path) = Files.readAttributes(candidate,
                "unix:uid,mode,nlink,ino,dev,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS)
            fun number(values: Map<String, Any>, key: String) =
                (values[key] as? Number)?.toLong() ?: unavailable()
            val parentBefore = attributes(directory)
            val before = attributes(path)
            if (number(parentBefore, "uid") != 0L ||
                (number(parentBefore, "mode") and 0xFFFFL) != 0x41C0L ||
                number(before, "uid") != 0L ||
                (number(before, "mode") and 0xFFFFL) != 0x8100L ||
                number(before, "nlink") != 1L ||
                number(before, "size") !in 1L..limit.toLong()) unavailable()
            val bytes = Files.newByteChannel(path,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() != number(before, "size")) unavailable()
                Channels.newInputStream(channel).readNBytes(limit + 1).also {
                    if (channel.size() != number(before, "size")) unavailable()
                }
            }
            if (attributes(path) != before || attributes(directory) != parentBefore ||
                bytes.size.toLong() != number(before, "size")) unavailable()
            return bytes
        }

        private fun childPath(value: String): Path = Path.of(value).also {
            if (it.parent != directory || it.fileName.toString() in setOf("installation.json", "root-pins.json") ||
                it.normalize() != it) unavailable()
        }
        private fun json(bytes: ByteArray, keys: Set<String>): JsonNode {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
            return mapper.readTree(bytes).also { requireKeys(it, keys) }
        }
        private fun requireKeys(node: JsonNode?, keys: Set<String>) {
            if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != keys ||
                keys.any { node[it].isNull }) unavailable()
        }
        private fun string(node: JsonNode, key: String): String =
            node[key]?.takeIf { it.isTextual }?.textValue() ?: unavailable()
        private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun unavailable(): Nothing = throw SelectedSourceUnavailable()
    }
}
