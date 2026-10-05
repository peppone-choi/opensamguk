package opensamguk.engine.boot

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.D101EffectiveSeedOptionsProvenance
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Byte transport only. Production must use the reviewed pinned native helper;
 * a synthetic reader in a unit test supplies no native custody or authority. */
fun interface D101PreIntentNativeReader { fun read(action: String): ByteArray }

/** Payload FD custody is the Go helper's responsibility. Java verifies the
 * fixed executable/source pin before/after; it does not claim native fstat. */
internal class D101PinnedPreIntentNativeReader(private val executable: Path, private val executableSha256: String) : D101PreIntentNativeReader {
    override fun read(action: String): ByteArray {
        if (action != D101NativePreIntentInputsSource.ACTION || !slots.tryAcquire()) unavailable()
        var process: Process? = null
        var workerStarted = false
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            verifyExecutable(deadline)
            val child = ProcessBuilder(executable.toString(), action).apply {
                environment().clear()
                redirectError(ProcessBuilder.Redirect.DISCARD)
            }.start()
            process = child
            child.outputStream.close()
            val done = CountDownLatch(1)
            val result = AtomicReference<ByteArray?>()
            val worker = Thread({
                try {
                    child.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer, 0, minOf(buffer.size, LIMIT + 1 - out.size()))
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buffer, 0, n)
                            if (out.size() > LIMIT || System.nanoTime() >= deadline) unavailable()
                        }
                        result.set(out.toByteArray())
                    }
                } catch (_: Exception) { result.set(null) }
                finally { slots.release(); done.countDown() }
            }, "d101-pre-intent-native-reader").apply { isDaemon = true }
            worker.start()
            workerStarted = true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || !done.await(remaining, TimeUnit.NANOSECONDS) ||
                !child.waitFor(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) || child.exitValue() != 0 ||
                System.nanoTime() >= deadline) unavailable()
            verifyExecutable(deadline)
            return result.get()?.takeIf { it.isNotEmpty() } ?: unavailable()
        } catch (_: Exception) { unavailable() }
        finally { process?.destroyForcibly(); if (!workerStarted) slots.release() }
    }

    private fun verifyExecutable(deadline: Long) {
        if (!Regex("[a-f0-9]{64}").matches(executableSha256) || !executable.isAbsolute || executable.normalize() != executable ||
            executable.toRealPath() != executable || System.nanoTime() >= deadline) unavailable()
        val parent = executable.parent ?: unavailable()
        val parentUnix = Files.readAttributes(parent, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
        if ((parentUnix["uid"] as? Number)?.toLong() != 0L || (parentUnix["mode"] as? Number)?.toInt()?.and(4095) != 448) unavailable()
        val before = Files.readAttributes(executable, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val unix = Files.readAttributes(executable, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.fileKey() == null || before.size() !in 1..(32L shl 20) ||
            (unix["uid"] as? Number)?.toLong() != 0L || (unix["nlink"] as? Number)?.toLong() != 1L ||
            (unix["mode"] as? Number)?.toInt()?.and(4095) != 320) unavailable()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(executable, setOf<java.nio.file.OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() != before.size()) unavailable()
            java.nio.channels.Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(8192)
                var count = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    if (count > before.size() || System.nanoTime() >= deadline) unavailable()
                    digest.update(buffer, 0, n)
                }
                if (count != before.size()) unavailable()
            }
        }
        val after = Files.readAttributes(executable, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val afterUnix = Files.readAttributes(executable, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS)
        val parentAfter = Files.readAttributes(parent, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
        if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            unix != afterUnix || parentUnix != parentAfter || HexFormat.of().formatHex(digest.digest()) != executableSha256 || System.nanoTime() >= deadline) unavailable()
    }
    private fun unavailable(): Nothing = throw SelectedSourceUnavailable()
    companion object { private const val LIMIT = 2 * 1024 * 1024; private val slots = Semaphore(2, true) }
}

data class D101PreIntentInputScope(
    val originalOp: String,
    val targetFingerprint: String,
    val appSourceSha: String,
    val imagePins: Map<String, String>,
    val artifactsRoot: Path,
)

/** Config/target precede selection. This source never reads a completed capture
 * index, selected receipt, approved material or future intent/card/command.
 * CID/image/mount authority remains the separate approved Root installer. */
class D101NativePreIntentInputsSource(
    private val reader: D101PreIntentNativeReader?,
    private val installationSha256: String,
    scope: D101PreIntentInputScope,
) : D101PreIntentInputsSource {
    private val expected = scope.copy(imagePins = scope.imagePins.toMap())
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun readFixedInputs(): D101PreIntentFixedInputs = try {
        if (!SHA.matches(installationSha256) || !Regex("[a-f0-9]{32}").matches(expected.originalOp) ||
            !SHA.matches(expected.targetFingerprint) || !Regex("[a-f0-9]{40}").matches(expected.appSourceSha) ||
            expected.imagePins.keys != IMAGE_IDS || expected.imagePins.values.any { !DIGEST.matches(it) } ||
            !expected.artifactsRoot.isAbsolute || expected.artifactsRoot.normalize() != expected.artifactsRoot ||
            expected.artifactsRoot == Path.of("/")) unavailable()
        val native = reader ?: unavailable()
        val raw = native.read(ACTION)
        val response = parse(raw, RESPONSE_KEYS, 144 * 1024)
        if (one(response["schemaVersion"]) != 1 || text(response["preIntentInstallationSha256"]) != installationSha256) unavailable()
        val manifestBytes = decode(text(response["preIntentInstallationBytesBase64url"]), 16 * 1024)
        if (hash(manifestBytes) != installationSha256) unavailable()
        val manifest = parse(manifestBytes, MANIFEST_KEYS, 16 * 1024)
        if (one(manifest["schemaVersion"]) != 1 || text(manifest["kind"]) != "D101_PRE_INTENT_INPUT_BINDINGS_V1" ||
            text(manifest["originalOp"]) != expected.originalOp || text(manifest["typedTargetFingerprint"]) != expected.targetFingerprint ||
            text(manifest["appSourceSha"]) != expected.appSourceSha || stringMap(manifest["imagePins"], IMAGE_IDS) != expected.imagePins ||
            text(manifest["artifactsRoot"]) != expected.artifactsRoot.toString() ||
            !Files.isDirectory(expected.artifactsRoot, LinkOption.NOFOLLOW_LINKS) || expected.artifactsRoot.toRealPath() != expected.artifactsRoot) unavailable()
        val configBytes = boundOriginal(response, manifest, "configurationBytesBase64url", "configurationOriginal", 64 * 1024)
        val targetBytes = boundOriginal(response, manifest, "typedTargetBytesBase64url", "typedTargetOriginal", 16 * 1024)
        if (hash(targetBytes) != expected.targetFingerprint) unavailable()
        val config = parse(configBytes, CONFIG_KEYS, 64 * 1024)
        if (one(config["schemaVersion"]) != 1 || text(config["kind"]) != "D101_EFFECTIVE_SEED_INPUTS_V1" ||
            text(config["originalOp"]) != expected.originalOp || text(config["typedTargetFingerprint"]) != expected.targetFingerprint ||
            text(config["appSourceSha"]) != expected.appSourceSha || stringMap(config["imagePins"], IMAGE_IDS) != expected.imagePins) unavailable()
        val options = stringMap(config["rawInputs"], D101EffectiveSeedOptionsProvenance.ALLOWED).mapValues { (key, value) ->
            if (key in NUMERIC_OPTIONS) {
                val input = value.trim()
                if (!Regex("[0-9]+").matches(input)) unavailable()
                input.toIntOrNull()?.toString() ?: unavailable()
            } else value
        }
        val wrapper = parse(targetBytes, setOf("id", "target"), 16 * 1024)
        if (text(wrapper["id"]) != "pep") unavailable()
        val target = wrapper["target"]
        keys(target, TARGET_KEYS)
        if (text(target["scenarioCode"]) != "scenario_3190" || !target["generation"].isIntegralNumber ||
            target["generation"].bigIntegerValue().signum() != 0 || !target["scenarioSeedEnabled"].isBoolean ||
            !target["scenarioSeedEnabled"].booleanValue() || stringMap(target["updates"], D101EffectiveSeedOptionsProvenance.ALLOWED) != options ||
            stringMap(target["storageImageDigests"], STORAGE_IDS) != expected.imagePins.filterKeys { it in STORAGE_IDS } ||
            stringMap(target["imageDigests"], APP_IDS) != expected.imagePins.filterKeys { it in APP_IDS }) unavailable()
        // These are supplied inputs, not observed importer results. C4's actual
        // same-importer callback must independently compare its parsed eleven.
        val after = native.read(ACTION)
        if (!after.contentEquals(raw) || expected.artifactsRoot.toRealPath() != expected.artifactsRoot) unavailable()
        D101PreIntentFixedInputs(options, expected.artifactsRoot)
    } catch (_: Exception) { unavailable() }

    private fun boundOriginal(response: JsonNode, manifest: JsonNode, payload: String, role: String, limit: Int): ByteArray {
        val ref = manifest[role]
        keys(ref, setOf("rawSha256", "byteLength"))
        val sha = text(ref["rawSha256"])
        val length = ref["byteLength"]
        if (!SHA.matches(sha) || !length.isIntegralNumber || !length.canConvertToInt() || length.intValue() !in 1..limit) unavailable()
        return decode(text(response[payload]), limit).also {
            if (it.size != length.intValue() || hash(it) != sha) unavailable()
        }
    }
    private fun parse(wire: ByteArray, required: Set<String>, limit: Int): JsonNode {
        if (wire.isEmpty() || wire.size > limit || wire.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) unavailable()
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(wire))
        return mapper.readTree(wire).also { keys(it, required) }
    }
    private fun keys(node: JsonNode?, required: Set<String>) {
        if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != required || required.any { node[it] == null || node[it].isNull }) unavailable()
    }
    private fun text(node: JsonNode?): String = node?.takeIf { it.isTextual }?.textValue() ?: unavailable()
    private fun one(node: JsonNode?): Int {
        if (node == null || !node.isIntegralNumber || !node.canConvertToInt() || node.intValue() != 1) unavailable()
        return 1
    }
    private fun stringMap(node: JsonNode?, required: Set<String>): Map<String, String> {
        keys(node, required)
        return required.associateWith { text(node!![it]) }
    }
    private fun decode(value: String, limit: Int): ByteArray {
        if (value.isEmpty() || value.length > (limit * 4 + 2) / 3 || !Regex("[A-Za-z0-9_-]+").matches(value)) unavailable()
        return Base64.getUrlDecoder().decode(value).also {
            if (it.isEmpty() || it.size > limit || Base64.getUrlEncoder().withoutPadding().encodeToString(it) != value) unavailable()
        }
    }
    private fun hash(wire: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire))
    private fun unavailable(): Nothing = throw SelectedSourceUnavailable()

    companion object {
        const val ACTION = "read-pre-intent-inputs"
        private val SHA = Regex("[a-f0-9]{64}")
        private val DIGEST = Regex("sha256:[a-f0-9]{64}")
        private val IMAGE_IDS = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        private val STORAGE_IDS = setOf("game-postgres", "game-redis")
        private val APP_IDS = IMAGE_IDS - STORAGE_IDS
        private val NUMERIC_OPTIONS = setOf("SERVER_GENERATION", "RESET_MAXGENERAL", "RESET_EXTEND", "RESET_TURNTERM", "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL", "RESET_FICTION")
        private val RESPONSE_KEYS = setOf("schemaVersion", "preIntentInstallationSha256", "preIntentInstallationBytesBase64url", "configurationBytesBase64url", "typedTargetBytesBase64url")
        private val MANIFEST_KEYS = setOf("schemaVersion", "kind", "originalOp", "typedTargetFingerprint", "appSourceSha", "imagePins", "configurationOriginal", "typedTargetOriginal", "artifactsRoot")
        private val CONFIG_KEYS = setOf("schemaVersion", "kind", "originalOp", "typedTargetFingerprint", "appSourceSha", "imagePins", "rawInputs")
        private val TARGET_KEYS = setOf("storageImageDigests", "scenarioCode", "generation", "scenarioSeedEnabled", "updates", "imageDigests")
    }
}
