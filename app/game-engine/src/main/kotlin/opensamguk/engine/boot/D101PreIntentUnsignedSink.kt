package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.D101EffectiveSeedOptionsProvenance
import opensamguk.infra.seed.D101SelectedSourceProducer
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock

/** Whole unsigned originals, never a hash-only stdout receipt. Only Root's
 * native reread and independent source checks can acknowledge or sign them. */
internal object D101PreIntentUnsignedSink {
    const val OUTPUT_PATH = "/run/d101/pre-intent-output"
    private val mapper = ObjectMapper()
    private val rawIds = setOf("selected-scenario.json", "classpath-scenario.json", "tiles.json", "world.json", "roads.json")
    private val required = rawIds + setOf("captureFacts", "typedTarget", "configuration", "parserClass", "resolverDecision",
        "topologyRootClass", "topologyCanonical", "selectedWorld", "parsedOptions")

    internal class Capture(originals: Map<String, ByteArray>, manifest: ByteArray) {
        private val originals = originals.mapValues { it.value.copyOf() }
        private val manifest = manifest.copyOf()
        fun originals() = originals.mapValues { it.value.copyOf() }
        fun manifest() = manifest.copyOf()
    }

    fun assemble(profile: D101PreIntentSourceProfile, inputOriginals: Map<String, ByteArray>,
        snapshot: D101PreIntentSelectedSnapshot, clock: Clock = Clock.systemUTC()): Capture = try {
        if (inputOriginals.keys != setOf("configuration", "typedTarget")) unavailable()
        val inputs = inputOriginals.mapValues { it.value.copyOf() }
        val scope = profile.scope
        val raw = snapshot.rawOriginals()
        val parser = snapshot.parserClassOriginal()
        val algorithm = snapshot.topologyRootClassOriginal()
        val canonical = snapshot.world.canonicalTopologyBytes()
        val topology = snapshot.world.topologyOriginals()
        val options = snapshot.effectiveOptions()
        if (raw.keys != rawIds || options.keys != D101EffectiveSeedOptionsProvenance.ALLOWED ||
            snapshot.inputs.parsedImporterOptions != options - setOf("SERVER_NAME", "SERVER_GENERATION") ||
            snapshot.inputs.effectiveResetExtend !in 0..1 || snapshot.inputs.resetExtendInput !in setOf("0", "1") ||
            snapshot.inputs.effectiveResetExtend.toString() != options["RESET_EXTEND"] || snapshot.inputs.resetExtendInput != options["RESET_EXTEND"] ||
            snapshot.inputs.mapResourceLogicalId != "map/han-world-v3.json" ||
            !raw.getValue("selected-scenario.json").contentEquals(snapshot.inputs.scenarioOriginal.originalBytes()) ||
            !raw.getValue("world.json").contentEquals(snapshot.inputs.mapOriginalBytes()) ||
            snapshot.world.mapOriginals().any { (id, wire) -> raw[id]?.contentEquals(wire) != true } ||
            d101PreIntentSha(inputs.getValue("typedTarget")) != scope.targetFingerprint ||
            topology.isEmpty() || topology.size > 32 || topology.keys.any { !validTopologyId(it) } ||
            d101PreIntentSha(canonical) != snapshot.world.topologyContentHash || canonical.size > 2 * 1024 * 1024) unavailable()
        for (bytecode in listOf(parser, algorithm)) if (bytecode.size !in 4..(2 * 1024 * 1024) ||
            !bytecode.copyOfRange(0, 4).contentEquals(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte()))) unavailable()
        val parsed = D101EffectiveSeedOptionsProvenance.capture(inputs.getValue("configuration"), d101PreIntentSha(inputs.getValue("configuration")),
            options, parser, d101PreIntentSha(parser))
        if (parsed.originalOp != scope.originalOp || parsed.targetFingerprint != scope.targetFingerprint ||
            parsed.appSourceSha != scope.appSourceSha || parsed.imagePins() != scope.imagePins) unavailable()
        val pins = raw.toSortedMap().mapValues { (id, wire) ->
            linkedMapOf<String, Any>("logicalArtifactId" to id, "rawSha256" to d101PreIntentSha(wire), "byteLength" to wire.size.toLong())
        }
        val decision = mapper.writeValueAsBytes(linkedMapOf<String, Any>("schemaVersion" to 1, "kind" to "D101_RESOLVER_DECISION_V1",
            "originalOp" to scope.originalOp, "typedTargetFingerprint" to scope.targetFingerprint, "appSourceSha" to scope.appSourceSha,
            "imagePins" to scope.imagePins, "selectedOrigin" to snapshot.inputs.scenarioOriginal.origin.name,
            "selectedLogicalId" to snapshot.inputs.scenarioOriginal.logicalId, "classpathLogicalId" to "scenario/scenario_3190.json", "originalPins" to pins))
        // FINAL_SELECTED and identity in these bytes are proposals. No signature
        // exists here, and Root must recompute all claims before using its key.
        val facts = D101SelectedSourceProducer(clock).produceCapturedBytes(raw, snapshot.inputs.scenarioOriginal,
            "scenario/scenario_3190.json", snapshot.world, parsed, algorithm, inputs.getValue("typedTarget"), decision, profile.producerIdentity)
        val selectedWorld = mapper.writeValueAsBytes(linkedMapOf<String, Any>("artifactSetId" to snapshot.world.artifactSetId,
            "variant" to snapshot.world.variant, "topologyRevision" to snapshot.world.topologyRevision,
            "topologyContentHash" to snapshot.world.topologyContentHash, "worldId" to 1,
            "mapResourceLogicalId" to snapshot.inputs.mapResourceLogicalId))
        val parsedOptions = mapper.writeValueAsBytes(linkedMapOf<String, Any>("parsedImporterOptions" to snapshot.inputs.parsedImporterOptions,
            "effectiveOptions" to options, "effectiveResetExtend" to snapshot.inputs.effectiveResetExtend,
            "configuredResetExtend" to (snapshot.inputs.resetExtendInput ?: unavailable())))
        val originals = raw + inputs + mapOf("parserClass" to parser, "topologyRootClass" to algorithm,
            "topologyCanonical" to canonical, "resolverDecision" to decision, "captureFacts" to facts,
            "selectedWorld" to selectedWorld, "parsedOptions" to parsedOptions) + topology.mapKeys { (id, _) -> "topology-input:$id" }
        if (originals.keys != required + topology.keys.map { "topology-input:$it" } || originals.any { (id, wire) ->
                wire.isEmpty() || wire.size > limit(id)
            }) unavailable()
        val refs = originals.toSortedMap().mapValues { (id, wire) -> linkedMapOf<String, Any>(
            "rawSha256" to d101PreIntentSha(wire), "byteLength" to wire.size.toLong(), "mediaType" to mediaType(id)) }
        val capturedAt = mapper.readTree(facts)["capturedAtUtc"].textValue()
        val manifest = mapper.writeValueAsBytes(linkedMapOf<String, Any>("schemaVersion" to 1, "kind" to "D101_PRE_INTENT_CAPTURE_V1",
            "originalOp" to scope.originalOp, "typedTargetFingerprint" to scope.targetFingerprint, "appSourceSha" to scope.appSourceSha,
            "imagePins" to scope.imagePins, "preIntentInstallationSha256" to profile.preIntentInstallationSha256,
            "capturedAtUtc" to capturedAt, "originals" to refs))
        if (manifest.size > 64 * 1024) unavailable()
        Capture(originals, manifest)
    } catch (_: Exception) { unavailable() }

    fun publishFixed(capture: Capture): Unit = try {
        val dir = Path.of(OUTPUT_PATH)
        val before = checkDirectory(dir)
        Files.newDirectoryStream(dir).use { if (it.iterator().hasNext()) unavailable() }
        for ((id, wire) in capture.originals().toSortedMap()) {
            writeOnce(dir.resolve(d101PreIntentSha(id.toByteArray(Charsets.UTF_8)) + ".bin"), wire)
            if (checkDirectory(dir).fileKey() != before.fileKey()) unavailable()
        }
        // The manifest is the last durable original. No partial directory is
        // cleared or overwritten on failure, even when this write is uncertain.
        writeOnce(dir.resolve("capture-manifest.json"), capture.manifest())
        if (checkDirectory(dir).fileKey() != before.fileKey()) unavailable()
        Unit
    } catch (_: Exception) { unavailable() }

    private fun checkDirectory(dir: Path): BasicFileAttributes {
        if (dir.toRealPath() != dir) unavailable()
        val attributes = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val unix = Files.readAttributes(dir, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.fileKey() == null || (unix["uid"] as? Number)?.toLong() != 0L ||
            (unix["mode"] as? Number)?.toInt()?.and(4095) != 448) unavailable()
        return attributes
    }
    private fun writeOnce(path: Path, wire: ByteArray) {
        FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"))).use { channel ->
            val buffer = ByteBuffer.wrap(wire)
            while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) unavailable()
            channel.force(true)
        }
        val unix = Files.readAttributes(path, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS)
        if ((unix["uid"] as? Number)?.toLong() != 0L || (unix["mode"] as? Number)?.toInt()?.and(4095) != 256 ||
            (unix["nlink"] as? Number)?.toLong() != 1L) unavailable()
        FileChannel.open(path.parent, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }
    internal fun limit(id: String): Int = when {
        id.endsWith("scenario.json") -> 16 * 1024 * 1024
        id == "parserClass" || id == "topologyRootClass" -> 2 * 1024 * 1024
        id == "typedTarget" -> 16 * 1024
        id in setOf("captureFacts", "configuration", "resolverDecision", "selectedWorld", "parsedOptions") -> 64 * 1024
        id in setOf("tiles.json", "world.json", "roads.json", "topologyCanonical") || id.startsWith("topology-input:") -> 64 * 1024 * 1024
        else -> unavailable()
    }
    internal fun mediaType(id: String): String = if (id == "parserClass" || id == "topologyRootClass" ||
        id == "topologyCanonical" || id == "topology-input:dryLandProjectionPolicy") "application/octet-stream" else "application/json"
    private fun validTopologyId(id: String): Boolean = Regex("[A-Za-z0-9._/-]{1,256}").matches(id) &&
        !Path.of(id).isAbsolute && Path.of(id).normalize().toString() == id && id != "." && id != ".." && !id.startsWith("../")
    private fun unavailable(): Nothing = throw SelectedSourceUnavailable()
}
