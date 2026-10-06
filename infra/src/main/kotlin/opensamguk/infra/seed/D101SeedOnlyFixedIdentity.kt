package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections

/** Independent Root-installed trust data, separate from installation17 and
 * signed request material. Only the fixed RO child mount can construct it.
 * Java path checks complement the Root's native FD/custody and mount checks;
 * they are not presented as an independent native host authority verifier. */
class D101SeedOnlyFixedIdentity private constructor(
    val producerIdentity: String,
    spki: ByteArray,
    val publicKeySpkiSha256: String,
    images: Map<String, String>,
) {
    private val key = spki.copyOf()
    private val images = Collections.unmodifiableMap(images.toMap())
    fun publicKeySpki(): ByteArray = key.copyOf()
    fun imagePins(): Map<String, String> = images

    companion object {
        private val fixedPath = Path.of("/run/d101/root-pins.json")
        private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val imageNames = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")

        fun readInstalled(): D101SeedOnlyFixedIdentity = try {
            val parent = fixedPath.parent
            if (parent.toRealPath() != parent) unavailable()
            fun attributes(path: Path) = Files.readAttributes(path,
                "unix:uid,mode,nlink,ino,dev,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS)
            fun number(values: Map<String, Any>, name: String) = (values[name] as? Number)?.toLong() ?: unavailable()
            fun validParent(values: Map<String, Any>): Boolean = number(values, "uid") == 0L &&
                (number(values, "mode") and 0xFFFFL) == 0x41C0L
            fun validFile(values: Map<String, Any>): Boolean = number(values, "uid") == 0L &&
                (number(values, "mode") and 0xFFFFL) == 0x8100L && number(values, "nlink") == 1L &&
                number(values, "size") in 1L..8192L
            val directoryBefore = attributes(parent)
            val before = attributes(fixedPath)
            if (!validParent(directoryBefore) || !validFile(before)) unavailable()
            val started = System.nanoTime()
            val wire = Files.newByteChannel(fixedPath,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() != number(before, "size")) unavailable()
                Channels.newInputStream(channel).readNBytes(8193).also {
                    if (channel.size() != number(before, "size")) unavailable()
                }
            }
            if (attributes(fixedPath) != before || attributes(parent) != directoryBefore ||
                wire.size.toLong() != number(before, "size") || System.nanoTime() - started >= 2_000_000_000L) unavailable()
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(wire))
            val root = mapper.readTree(wire)
            val keys = setOf("schemaVersion", "kind", "keyId", "publicKeySpkiBase64url", "publicKeySpkiSha256", "imagePins")
            if (!root.isObject || root.fieldNames().asSequence().toSet() != keys || keys.any { root[it].isNull } ||
                !root["schemaVersion"].isIntegralNumber || root["schemaVersion"].bigIntegerValue() != java.math.BigInteger.ONE ||
                !root["kind"].isTextual || root["kind"].textValue() != "D101_SEED_ROOT_PINS_V1") unavailable()
            fun text(name: String): String = root[name].let { if (!it.isTextual) unavailable(); it.textValue() }
            val identity = text("keyId")
            val sha = text("publicKeySpkiSha256")
            val encoded = text("publicKeySpkiBase64url")
            if (!identity.matches(Regex("[a-z0-9._-]{1,64}")) || !sha.matches(Regex("[a-f0-9]{64}")) ||
                encoded.length != 59 || !encoded.matches(Regex("[A-Za-z0-9_-]+"))) unavailable()
            val spki = Base64.getUrlDecoder().decode(encoded)
            if (spki.size != 44 || Base64.getUrlEncoder().withoutPadding().encodeToString(spki) != encoded ||
                selectedOriginalSha(spki) != sha || !spki.copyOfRange(0, 12).contentEquals(
                    java.util.HexFormat.of().parseHex("302a300506032b6570032100"))) unavailable()
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
            val pins = root["imagePins"]
            if (!pins.isObject || pins.fieldNames().asSequence().toSet() != imageNames) unavailable()
            val images = imageNames.associateWith { name -> pins[name].let {
                if (!it.isTextual || !it.textValue().matches(Regex("sha256:[a-f0-9]{64}"))) unavailable()
                it.textValue()
            } }
            D101SeedOnlyFixedIdentity(identity, spki, sha, images)
        } catch (_: Exception) { unavailable() }

        private fun unavailable(): Nothing = throw SelectedSourceUnavailable()
    }
}
