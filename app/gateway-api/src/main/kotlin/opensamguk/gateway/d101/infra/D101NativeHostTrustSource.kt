package opensamguk.gateway.d101.infra

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun interface D101NativeHostReader {
    fun read(action: String): ByteArray
}

/** Fixed executable, no shell/request arguments/env. Payload native custody is
 * enforced by the reviewed Go helper; this class does not claim Java FD fstat.
 * Deployment must pin/install this exact helper and reader-bindings original. */
internal class D101PinnedNativeHostReader(
    private val executable: Path,
    private val executableSha256: String,
) : D101NativeHostReader {
    override fun read(action: String): ByteArray {
        if (action !in setOf("read-originals", "read-token", "read-selected") || !slots.tryAcquire()) unavailable()
        var process: Process? = null
        var readerStarted = false
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            verifyExecutable(deadline)
            val child = ProcessBuilder(executable.toString(), action).apply {
                environment().clear()
                redirectError(ProcessBuilder.Redirect.DISCARD)
            }.start()
            process = child
            child.outputStream.close()
            val completed = CountDownLatch(1)
            val result = AtomicReference<ByteArray?>()
            val worker = Thread({
                try {
                    child.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = input.read(chunk, 0, minOf(chunk.size, LIMIT + 1 - out.size()))
                            if (count < 0) break
                            if (count == 0) continue
                            out.write(chunk, 0, count)
                            if (out.size() > LIMIT || System.nanoTime() >= deadline) unavailable()
                        }
                        result.set(out.toByteArray())
                    }
                } catch (_: Exception) {
                    result.set(null)
                } finally {
                    slots.release()
                    completed.countDown()
                }
            }, "d101-native-private-reader").apply { isDaemon = true }
            worker.start()
            readerStarted = true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || !completed.await(remaining, TimeUnit.NANOSECONDS) ||
                !child.waitFor(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) ||
                child.exitValue() != 0 || System.nanoTime() >= deadline) unavailable()
            verifyExecutable(deadline)
            return result.get()?.takeIf { it.isNotEmpty() } ?: unavailable()
        } catch (_: Exception) {
            unavailable()
        } finally {
            process?.destroyForcibly()
            if (!readerStarted) slots.release()
        }
    }

    private fun verifyExecutable(deadline:Long) {
        if (System.nanoTime()>=deadline) unavailable()
        if (!D101StrictJson.SHA.matches(executableSha256) || !executable.isAbsolute ||
            executable.normalize() != executable || executable.toRealPath() != executable) unavailable()
        val parent = executable.parent ?: unavailable()
        val parentAttrs = Files.readAttributes(parent, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
        if ((parentAttrs["uid"] as? Number)?.toLong() != 0L ||
            (parentAttrs["mode"] as? Number)?.toInt()?.and(4095) != 448) unavailable() // 0700
        val before = Files.readAttributes(executable, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val unix = Files.readAttributes(executable, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.fileKey() == null || before.size() !in 1..(32L shl 20) ||
            (unix["uid"] as? Number)?.toLong() != 0L || (unix["nlink"] as? Number)?.toLong() != 1L ||
            (unix["mode"] as? Number)?.toInt()?.and(4095) != 320) unavailable() // 0500
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(executable, setOf<java.nio.file.OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() != before.size()) unavailable()
            java.nio.channels.Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(8192)
                var length = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    length += n
                    if (length > before.size() || System.nanoTime()>=deadline) unavailable()
                    digest.update(buffer, 0, n)
                }
                if (length != before.size()) unavailable()
            }
        }
        val after = Files.readAttributes(executable, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val unixAfter = Files.readAttributes(executable, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS)
        if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime() || unix != unixAfter ||
            HexFormat.of().formatHex(digest.digest()) != executableSha256 || System.nanoTime()>=deadline) unavailable()
    }

    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()
    companion object { private const val LIMIT = 2 * 1024 * 1024; private val slots = Semaphore(2, true) }
}

/** The original reader-bindings SHA is pinned by the reviewed deployment card.
 * Native helper installation and source verification do not occur automatically. */
internal class D101NativeHostTrustSource(
    private val reader: D101NativeHostReader,
    private val readerBindingsSha256: String,
    mapper: ObjectMapper = ObjectMapper(),
) : D101FixedHostTrustSource, opensamguk.infra.seed.D101SignedSelectedReceiptReader {
    private val json = D101StrictJson(mapper)

    override fun readOriginals(): D101HostTrustOriginals = closed {
        val response = json.objectBytes(reader.read("read-originals"), setOf("schemaVersion", "installationSha256",
            "manifestEnvelopeBase64url", "clockEnvelopeBase64url", "originals"), 2 * 1024 * 1024)
        requireInstallation(response)
        val originals = response["originals"]
        json.requireKeys(originals, D101ApprovedPurposeAuthority.ORIGINAL_IDS)
        val bytes = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith {
            json.base64url(json.text(originals[it]), 64 * 1024)
        }
        if (D101StrictJson.hash(bytes.getValue("readerBindings")) != readerBindingsSha256) unavailable()
        D101HostTrustOriginals(json.base64url(json.text(response["manifestEnvelopeBase64url"]), 64 * 1024),
            json.base64url(json.text(response["clockEnvelopeBase64url"]), 64 * 1024), bytes)
    }

    override fun rootToken(): String = closed {
        val response = json.objectBytes(reader.read("read-token"), setOf("schemaVersion", "installationSha256", "rootToken"), 16 * 1024)
        requireInstallation(response)
        json.text(response["rootToken"]).also {
            if (it.length > 4096 || it.any { char -> char.code !in 33..126 }) unavailable()
        }
    }

    private fun requireInstallation(root: com.fasterxml.jackson.databind.JsonNode) {
        if (json.positiveLong(root["schemaVersion"]) != 1L || !D101StrictJson.SHA.matches(readerBindingsSha256) ||
            json.sha(root["installationSha256"]) != readerBindingsSha256) unavailable()
    }

    override fun readOriginalEnvelope(): ByteArray = closed {
        val response = json.objectBytes(reader.read("read-selected"), setOf("schemaVersion", "installationSha256",
            "selectedEnvelopeBase64url"), 144 * 1024)
        requireInstallation(response)
        json.base64url(json.text(response["selectedEnvelopeBase64url"]), 96 * 1024)
    }
    private fun <T> closed(block: () -> T): T = try { block() } catch (_: Exception) { unavailable() }
    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()
}
