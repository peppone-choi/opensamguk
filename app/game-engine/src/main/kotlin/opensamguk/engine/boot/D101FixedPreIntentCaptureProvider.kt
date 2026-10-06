package opensamguk.engine.boot

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** The fixed Root-mounted profile is independent of every helper response.
 * Root must verify its independently selected SHA and native custody before
 * create/start. Java attribute checks alone never grant that host authority. */
class D101FixedPreIntentCaptureProvider : D101PreIntentCaptureProvider {
    override fun install(): D101PreIntentCaptureInstallation {
        val original = D101PreIntentSourceProfile.readFixedOriginal()
        val profile = D101PreIntentSourceProfile.decode(original)
        val source = D101NativePreIntentInputsSource(
            D101PinnedPreIntentNativeReader(Path.of(profile.nativeHelperPath), profile.nativeHelperSha256),
            profile.preIntentInstallationSha256, profile.scope)
        return object : D101PreIntentCaptureInstallation {
            override val fixedSource = source
            private var delivered = false
            private fun recheck() {
                if (!D101PreIntentSourceProfile.readFixedOriginal().contentEquals(original)) throw SelectedSourceUnavailable()
            }
            override fun deliverUnsigned(snapshot: D101PreIntentSelectedSnapshot) {
                if (delivered) throw SelectedSourceUnavailable()
                delivered = true // A partial write cannot become an implicit retry.
                recheck()
                D101PreIntentUnsignedSink.publishFixed(
                    D101PreIntentUnsignedSink.assemble(profile, source.capturedInputOriginals(), snapshot))
                recheck()
            }
            override fun close() { recheck() }
        }
    }
}

internal class D101PreIntentSourceProfile private constructor(
    val scope: D101PreIntentInputScope,
    val preIntentInstallationSha256: String,
    val nativeHelperPath: String,
    val nativeHelperSha256: String,
    val producerIdentity: String,
) {
    companion object {
        const val PROFILE_PATH = "/etc/opensamguk/d101/pre-intent-source-pins.json"
        const val HELPER_PATH = "/etc/opensamguk/d101/native-helper/d101-host-reader"
        private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val fields = setOf("schemaVersion", "kind", "originalOp", "typedTargetFingerprint", "appSourceSha",
            "imagePins", "artifactsRoot", "preIntentInstallationSha256", "nativeHelperPath", "nativeHelperSha256", "producerIdentity")
        private val images = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        internal fun decode(original: ByteArray): D101PreIntentSourceProfile = try {
            if (original.isEmpty() || original.size > 16 * 1024) unavailable()
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original))
            val node = mapper.readTree(original)
            if (!node.isObject || node.fieldNames().asSequence().toSet() != fields || fields.any { node[it].isNull } ||
                !node["schemaVersion"].isIntegralNumber || node["schemaVersion"].bigIntegerValue() != java.math.BigInteger.ONE) unavailable()
            fun text(key: String): String = node[key].takeIf { it.isTextual }?.textValue() ?: unavailable()
            val op = text("originalOp"); val target = text("typedTargetFingerprint"); val app = text("appSourceSha")
            val installation = text("preIntentInstallationSha256"); val helperSha = text("nativeHelperSha256")
            val identity = text("producerIdentity")
            val imageNode = node["imagePins"]
            if (text("kind") != "D101_PRE_INTENT_SOURCE_PINS_V1" || !Regex("[a-f0-9]{32}").matches(op) ||
                !Regex("[a-f0-9]{40}").matches(app) || listOf(target, installation, helperSha).any { !Regex("[a-f0-9]{64}").matches(it) } ||
                !Regex("[a-z0-9._-]{1,64}").matches(identity) || text("artifactsRoot") != "/app" ||
                text("nativeHelperPath") != HELPER_PATH || !imageNode.isObject || imageNode.fieldNames().asSequence().toSet() != images) unavailable()
            val pins = images.associateWith { id ->
                imageNode[id].takeIf { it.isTextual && Regex("sha256:[a-f0-9]{64}").matches(it.textValue()) }?.textValue() ?: unavailable()
            }
            D101PreIntentSourceProfile(D101PreIntentInputScope(op, target, app, pins, Path.of("/app")), installation, HELPER_PATH, helperSha, identity)
        } catch (_: Exception) { unavailable() }

        internal fun readFixedOriginal(): ByteArray = try {
            val path = Path.of(PROFILE_PATH)
            if (path.toRealPath() != path) unavailable()
            val parent = path.parent
            val parentBefore = Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val parentUnix = Files.readAttributes(parent, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
            if (!parentBefore.isDirectory || parentBefore.fileKey() == null || (parentUnix["uid"] as? Number)?.toLong() != 0L ||
                (parentUnix["mode"] as? Number)?.toInt()?.and(4095) != 448) unavailable()
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val unix = Files.readAttributes(path, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS)
            if (!before.isRegularFile || before.fileKey() == null || before.size() !in 1..(16L * 1024) ||
                (unix["uid"] as? Number)?.toLong() != 0L || (unix["mode"] as? Number)?.toInt()?.and(4095) != 256 ||
                (unix["nlink"] as? Number)?.toLong() != 1L) unavailable()
            val raw = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != before.size()) unavailable()
                val buffer = ByteBuffer.allocate(before.size().toInt())
                while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) unavailable()
                if (channel.read(ByteBuffer.allocate(1)) != -1 || channel.size() != before.size()) unavailable()
                buffer.array()
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val parentAfter = Files.readAttributes(parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (before.fileKey() != after.fileKey() || before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
                unix != Files.readAttributes(path, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS) ||
                parentBefore.fileKey() != parentAfter.fileKey() || parentBefore.lastModifiedTime() != parentAfter.lastModifiedTime() ||
                parentUnix != Files.readAttributes(parent, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)) unavailable()
            raw
        } catch (_: Exception) { unavailable() }
        private fun unavailable(): Nothing = throw SelectedSourceUnavailable()
    }
}

internal fun d101PreIntentSha(original: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original))
