package opensamguk.infra.seed

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat

enum class SelectedScenarioOrigin { EXTERNAL, CLASSPATH }

/** One actual read, before UTF8 conversion. This captures source facts, not
 * approved runtime/image provenance or a FINAL_SELECTED receipt. External
 * path attributes before/after are not presented as native FD custody; that
 * independent approved host verification is still required by the producer. */
class CapturedScenarioOriginal private constructor(
    val origin: SelectedScenarioOrigin,
    val logicalId: String,
    original: ByteArray,
) {
    private val bytes = original.copyOf()
    val rawSha256: String = selectedOriginalSha(bytes)
    val byteLength: Long = bytes.size.toLong()
    fun originalBytes(): ByteArray = bytes.copyOf()
    fun openOriginal(): InputStream = ByteArrayInputStream(bytes.copyOf())
    fun utf8(): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    companion object {
        private const val MAX_SCENARIO_BYTES = 16 * 1024 * 1024

        internal fun external(path: Path, logicalId: String): CapturedScenarioOriginal {
            val absolute = path.toAbsolutePath().normalize()
            val before = Files.readAttributes(absolute, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!before.isRegularFile || before.fileKey() == null || before.size() <= 0 || before.size() > MAX_SCENARIO_BYTES ||
                absolute.toRealPath() != absolute) throw SelectedSourceUnavailable()
            // Exactly one source channel. Attribute checks don't read or select
            // another payload, and failure never falls back to classpath.
            val wire = Files.newByteChannel(absolute, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() != before.size()) throw SelectedSourceUnavailable()
                val stream = java.nio.channels.Channels.newInputStream(channel)
                boundedOriginal(stream).also { if (channel.size() != before.size()) throw SelectedSourceUnavailable() }
            }
            val after = Files.readAttributes(absolute, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!after.isRegularFile || after.fileKey() != before.fileKey() || after.size() != before.size() ||
                after.lastModifiedTime() != before.lastModifiedTime() || wire.size.toLong() != before.size()) throw SelectedSourceUnavailable()
            return CapturedScenarioOriginal(SelectedScenarioOrigin.EXTERNAL, logicalId, wire)
        }

        internal fun classpath(classLoader: ClassLoader, resource: String): CapturedScenarioOriginal {
            val wire = classLoader.getResourceAsStream(resource)?.use(::boundedOriginal) ?: throw SelectedSourceUnavailable()
            return CapturedScenarioOriginal(SelectedScenarioOrigin.CLASSPATH, resource, wire)
        }

        private fun boundedOriginal(stream: InputStream): ByteArray {
            val wire = stream.readNBytes(MAX_SCENARIO_BYTES + 1)
            if (wire.isEmpty() || wire.size > MAX_SCENARIO_BYTES) throw SelectedSourceUnavailable()
            return wire
        }
    }
}

/** Installed only by the approved fixed source/custody producer. It must read
 * and verify actual originals/options/image pins/receipt provenance; returning
 * self-reported hashes or a synthetic receipt is not its implementation. */
fun interface SelectedSourceCustodySource {
    fun readVerified(
        originalOp: String,
        typedTargetFingerprint: String,
        appSourceSha: String,
        imagePins: Map<String, String>,
        originals: SelectedCapturedOriginals,
    ): SelectedBundleBinding
}

/** Read-only captured inputs for the producer's independent raw SHA/length
 * check. No absolute path, resolver, mutable array or default option exposed. */
class SelectedCapturedOriginals internal constructor(
    bytes: Map<String, ByteArray>,
    val selectedOrigin: SelectedScenarioOrigin,
    val selectedLogicalId: String,
    val classpathLogicalId: String,
) {
    private val originals = bytes.mapValues { it.value.copyOf() }
    fun pins(): Map<String, SelectedOriginalPin> = Collections.unmodifiableMap(originals.mapValues { (id, wire) ->
        SelectedOriginalPin(id, selectedOriginalSha(wire), wire.size.toLong())
    })
    fun openOriginal(logicalArtifactId: String): InputStream =
        ByteArrayInputStream((originals[logicalArtifactId] ?: throw SelectedSourceUnavailable()).copyOf())
}

class D101SelectedSourceCustody(private val approvedSource: SelectedSourceCustodySource? = null) {
    /** The same selectedScenario snapshot must supply the scenario parser.
     * mapOriginals must come from the actual selected immutable world bundle.
     * Actual producer availability is checked before a Verified handle exists. */
    fun capture(
        originalOp: String, typedTargetFingerprint: String, appSourceSha: String, imagePins: Map<String, String>,
        selectedScenario: CapturedScenarioOriginal, classpathScenario: CapturedScenarioOriginal,
        mapOriginals: Map<String, ByteArray>,
    ): VerifiedSelectedBundleHandle {
        val source = approvedSource ?: throw SelectedSourceUnavailable()
        if (!OP.matches(originalOp) || !SHA.matches(typedTargetFingerprint) || !GIT.matches(appSourceSha) ||
            imagePins.keys != FIVE_IMAGES || imagePins.values.any { !DIGEST.matches(it) } ||
            mapOriginals.keys != MAP_ORIGINALS || classpathScenario.origin != SelectedScenarioOrigin.CLASSPATH) throw SelectedSourceUnavailable()
        if (selectedScenario.origin == SelectedScenarioOrigin.CLASSPATH &&
            (selectedScenario.logicalId != classpathScenario.logicalId || selectedScenario.rawSha256 != classpathScenario.rawSha256 ||
                selectedScenario.byteLength != classpathScenario.byteLength)) throw SelectedSourceUnavailable()
        // Invalid UTF8 cannot be selected by one parser and certified as some
        // different replacement-decoded text by the source/receipt producer.
        try {
            selectedScenario.utf8()
            classpathScenario.utf8()
        } catch (_: CharacterCodingException) { throw SelectedSourceUnavailable() }
        val bytes = mapOriginals.mapValues { (_, original) ->
            if (original.isEmpty() || original.size > MAX_MAP_BYTES) throw SelectedSourceUnavailable()
            original.copyOf()
        }.toMutableMap()
        bytes["selected-scenario.json"] = selectedScenario.originalBytes()
        bytes["classpath-scenario.json"] = classpathScenario.originalBytes()
        val captured = SelectedCapturedOriginals(bytes, selectedScenario.origin, selectedScenario.logicalId, classpathScenario.logicalId)
        val pins = Collections.unmodifiableMap(imagePins.toMap())
        val binding = try {
            FrozenSelectedBundleBinding(source.readVerified(originalOp, typedTargetFingerprint, appSourceSha, pins, captured))
        } catch (_: Exception) { throw SelectedSourceUnavailable() }
        if (binding.originalOp != originalOp || binding.typedTargetFingerprint != typedTargetFingerprint || binding.appSourceSha != appSourceSha ||
            binding.imagePins() != pins || binding.selectionStatus != "FINAL_SELECTED" || binding.originals() != captured.pins() ||
            binding.scenarioOrigin != captured.selectedOrigin || binding.scenarioLogicalId != captured.selectedLogicalId || binding.classpathLogicalId != captured.classpathLogicalId ||
            !SHA.matches(binding.selectedSourceReceiptSha256) || selectedOriginalSha(binding.originalReceipt()) != binding.selectedSourceReceiptSha256 ||
            binding.originalReceipt().isEmpty() || binding.originalReceipt().size > 64 * 1024 ||
            listOf(binding.artifactSetId, binding.variant, binding.topologyRevision).any { it.isBlank() } ||
            !SHA.matches(binding.topologyContentHash) || !SHA.matches(binding.topologyContentHashProvenanceSha256) ||
            !binding.effectiveOptions().keys.containsAll(REQUIRED_OPTIONS) || !ALLOWED_OPTIONS.containsAll(binding.effectiveOptions().keys) ||
            binding.optionProvenance().keys != binding.effectiveOptions().keys ||
            binding.optionProvenance().values.any { !SHA.matches(it) }) throw SelectedSourceUnavailable()
        return CapturedSelectedBundleHandle(binding, bytes)
    }

    companion object {
        private val OP = Regex("[a-f0-9]{32}")
        private val SHA = Regex("[a-f0-9]{64}")
        private val GIT = Regex("[a-f0-9]{40}")
        private val DIGEST = Regex("sha256:[a-f0-9]{64}")
        private const val MAX_MAP_BYTES = 64 * 1024 * 1024
        private val MAP_ORIGINALS = setOf("tiles.json", "world.json", "roads.json")
        private val FIVE_IMAGES = setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        private val REQUIRED_OPTIONS = setOf("SERVER_NAME", "SERVER_GENERATION", "SCENARIO_CODE", "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR",
            "RESET_MAXGENERAL", "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM", "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL")
        private val ALLOWED_OPTIONS = REQUIRED_OPTIONS + setOf("RESET_SYNC", "RESET_FICTION", "RESET_AUTORUN_USER_OPTIONS", "RESET_AUTORUN_USER_MINUTES",
            "RESET_JOIN_MODE", "RESET_TOURNAMENT_TRIG", "RESET_RESERVE_OPEN", "RESET_PRE_RESERVE_OPEN")
    }
}

internal fun selectedOriginalSha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
