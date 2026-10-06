package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.seed.D101EffectiveSeedOptionsProvenance
import opensamguk.infra.seed.D101WorldArtifactCapture
import opensamguk.infra.seed.EffectiveScenarioResolver
import opensamguk.infra.seed.SelectedScenarioOrigin
import opensamguk.infra.seed.SelectedSourceUnavailable
import opensamguk.logic.world.StrategicTopologySnapshot
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** C8 must read these values from its fixed index and original config/target, not caller paths. */
fun interface D101PreIntentInputsSource {
    fun readFixedInputs(): D101PreIntentFixedInputs
}

class D101PreIntentFixedInputs(
    effectiveOptions: Map<String, String>,
    val artifactsRoot: Path,
) {
    private val options = effectiveOptions.toMap()
    fun effectiveOptions(): Map<String, String> = options.toMap()
}

/** Raw selected facts only. This is neither a signed receipt nor approval to write a database. */
class D101PreIntentSelectedSnapshot(
    val inputs: D101SelectedImportInputs,
    val world: D101WorldArtifactCapture,
    originals: Map<String, ByteArray>,
    parserClassOriginal: ByteArray,
    topologyRootClassOriginal: ByteArray,
    effectiveOptions: Map<String, String>,
) {
    private val raw = originals.mapValues { it.value.copyOf() }
    private val parser = parserClassOriginal.copyOf()
    private val topologyRoot = topologyRootClassOriginal.copyOf()
    private val options = effectiveOptions.toMap()
    fun rawOriginals(): Map<String, ByteArray> = raw.mapValues { it.value.copyOf() }
    fun parserClassOriginal(): ByteArray = parser.copyOf()
    fun topologyRootClassOriginal(): ByteArray = topologyRoot.copyOf()
    fun effectiveOptions(): Map<String, String> = options.toMap()
}

/** Uses the same SeedBootstrap importer factory as fresh seeding, with no JDBC handle. */
class D101PreIntentSelectedCapture(private val fixedSource: D101PreIntentInputsSource?) {
    fun capture(): D101PreIntentSelectedSnapshot = try {
        val source = fixedSource?.readFixedInputs() ?: throw SelectedSourceUnavailable()
        val options = source.effectiveOptions()
        if (options.keys != D101EffectiveSeedOptionsProvenance.ALLOWED ||
            options["SERVER_NAME"] != "빼섭" || options["SERVER_GENERATION"] != "0" ||
            options["SCENARIO_CODE"] != "scenario_3190" || options["SCENARIO_SEED_ENABLED"] != "true" ||
            options["SCENARIO_LOOKUP_DIR"] != "" || options["RESET_MAXGENERAL"] != "50" ||
            options["RESET_FIRST_TURN"] != "immediate" || options["RESET_TURNTERM"] != "60" ||
            options["RESET_BLOCK_GENERAL_CREATE"] != "1" || options["RESET_EXTEND"] !in setOf("0", "1") ||
            options["RESET_FICTION"] !in setOf("0", "1") ||
            options["RESET_NPCMODE"] !in setOf("0", "1", "2") ||
            options["RESET_SHOW_IMG_LEVEL"] !in setOf("0", "1", "2", "3")) {
            throw SelectedSourceUnavailable()
        }
        val importerKeys = options.keys - setOf("SERVER_NAME", "SERVER_GENERATION")
        val bootstrap = SeedBootstrap(
            scenarioCode = options.getValue("SCENARIO_CODE"),
            seedEnabled = options.getValue("SCENARIO_SEED_ENABLED") == "true",
            scenarioDir = options.getValue("SCENARIO_LOOKUP_DIR"),
            resetTurnTerm = options.getValue("RESET_TURNTERM"),
            resetMaxGeneral = options.getValue("RESET_MAXGENERAL"),
            resetFirstTurn = options.getValue("RESET_FIRST_TURN"),
            resetFiction = options.getValue("RESET_FICTION"),
            resetExtend = options.getValue("RESET_EXTEND"),
            resetBlockGeneralCreate = options.getValue("RESET_BLOCK_GENERAL_CREATE"),
            resetNpcMode = options.getValue("RESET_NPCMODE"),
            resetShowImgLevel = options.getValue("RESET_SHOW_IMG_LEVEL"),
            artifactsRoot = source.artifactsRoot,
            worldId = WorldId(1),
        )
        val inputs = bootstrap.captureSelectedImportInputsReadOnly()
        if (inputs.parsedImporterOptions != options.filterKeys { it in importerKeys } ||
            inputs.mapResourceLogicalId != "map/han-world-v3.json") throw SelectedSourceUnavailable()
        val selected = inputs.scenarioOriginal
        val classpathId = "scenario/scenario_3190.json"
        val classpath = if (selected.origin == SelectedScenarioOrigin.CLASSPATH) {
            if (selected.logicalId != classpathId) throw SelectedSourceUnavailable()
            selected.originalBytes()
        } else {
            if (selected.logicalId != "scenario_3190.json") throw SelectedSourceUnavailable()
            EffectiveScenarioResolver().readScenarioOriginal("scenario_3190").also {
                if (it.origin != SelectedScenarioOrigin.CLASSPATH || it.logicalId != classpathId) {
                    throw SelectedSourceUnavailable()
                }
            }.originalBytes()
        }
        val world = D101WorldArtifactCapture.capture(inputs.selectedWorld)
        val maps = world.mapOriginals()
        if (maps.keys != setOf("tiles.json", "world.json", "roads.json") ||
            !maps.getValue("world.json").contentEquals(inputs.mapOriginalBytes())) {
            throw SelectedSourceUnavailable()
        }
        val originals = mapOf(
            "selected-scenario.json" to selected.originalBytes(),
            "classpath-scenario.json" to classpath,
        ) + maps
        if (originals.size != 5 || originals.any { (name, bytes) ->
                bytes.isEmpty() || bytes.size > (if (name.endsWith("scenario.json")) 16 else 64) * 1024 * 1024
            }) throw SelectedSourceUnavailable()
        val canonical = world.canonicalTopologyBytes()
        val topologyInputs = world.topologyOriginals()
        if (canonical.isEmpty() || canonical.size > 64 * 1024 * 1024 ||
            sha256(canonical) != world.topologyContentHash ||
            topologyInputs.isEmpty() || topologyInputs.values.any {
                it.isEmpty() || it.size > 64 * 1024 * 1024
            }) throw SelectedSourceUnavailable()
        D101PreIntentSelectedSnapshot(inputs, world, originals,
            classOriginal(SeedBootstrap::class.java), classOriginal(StrategicTopologySnapshot::class.java), options)
    } catch (_: Exception) {
        throw SelectedSourceUnavailable()
    }

    private fun classOriginal(type: Class<*>): ByteArray {
        val name = "${type.simpleName}.class"
        val bytes = type.getResourceAsStream(name)?.use { it.readNBytes(2 * 1024 * 1024 + 1) }
            ?: throw SelectedSourceUnavailable()
        if (bytes.size < 4 || bytes.size > 2 * 1024 * 1024 ||
            !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(),
                0xBA.toByte(), 0xBE.toByte()))) throw SelectedSourceUnavailable()
        return bytes
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
