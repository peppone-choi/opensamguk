package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import opensamguk.common.world.WorldId
import opensamguk.infra.seed.D101SeedCapPromotionGate
import opensamguk.infra.seed.D101SelectedCaptureCoordinator
import opensamguk.infra.seed.SelectedSourceUnavailable
import org.springframework.jdbc.core.JdbcTemplate
import java.io.PrintStream
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.ServiceConfigurationError
import kotlin.system.exitProcess

/** Installed by the approved fixed source. It must bind its JDBC, roots, pins and coordinator to one candidate. */
interface D101SeedOnlyInstallation : AutoCloseable {
    val jdbc: JdbcTemplate
    val artifactsRoot: Path
    val actualOptions: Map<String, String>
    val originalOp: String
    val typedTargetFingerprint: String
    val appSourceSha: String
    val imagePins: Map<String, String>
    fun coordinatorFor(inputs: D101SelectedImportInputs): D101SelectedCaptureCoordinator
}

fun interface D101SeedOnlyInstaller {
    fun install(): D101SeedOnlyInstallation
}

/** Dedicated one-shot seed entrypoint. No Spring context, HTTP listener or turn scheduler is created. */
object D101SeedOnlyCli {
    private val importerOptions = setOf("SCENARIO_CODE", "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR",
        "RESET_MAXGENERAL", "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM",
        "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL", "RESET_FICTION")
    private val allOptions = importerOptions + setOf("SERVER_NAME", "SERVER_GENERATION")
    private val mapper = ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    /** Returns 0 only after fresh committed seed, selected-source check and read-only DB cap observation. */
    internal fun run(args: Array<String>, out: PrintStream = System.out): Int = try {
        if (args.toList() != listOf("--d101-seed-only-v1")) throw SelectedSourceUnavailable()
        val installers = ServiceLoader.load(D101SeedOnlyInstaller::class.java).toList()
        if (installers.size != 1) throw SelectedSourceUnavailable()
        installers.single().install().use { installed ->
            val options = installed.actualOptions.toMap()
            if (options.keys != allOptions || options["SCENARIO_CODE"] != "scenario_3190" ||
                options["SCENARIO_SEED_ENABLED"] != "true" || options["SERVER_NAME"] != "빼섭" ||
                options["SERVER_GENERATION"] != "0") throw SelectedSourceUnavailable()
            val consumer = D101SelectedImportConsumer(installed::coordinatorFor)
            var observed: D101SelectedImportObservation? = null
            val bootstrap = SeedBootstrap(
                scenarioCode = options.getValue("SCENARIO_CODE"),
                seedEnabled = true,
                scenarioDir = options.getValue("SCENARIO_LOOKUP_DIR"),
                resetTurnTerm = options.getValue("RESET_TURNTERM"),
                resetMaxGeneral = options.getValue("RESET_MAXGENERAL"),
                resetFirstTurn = options.getValue("RESET_FIRST_TURN"),
                resetFiction = options.getValue("RESET_FICTION"),
                resetExtend = options.getValue("RESET_EXTEND"),
                resetBlockGeneralCreate = options.getValue("RESET_BLOCK_GENERAL_CREATE"),
                resetNpcMode = options.getValue("RESET_NPCMODE"),
                resetShowImgLevel = options.getValue("RESET_SHOW_IMG_LEVEL"),
                artifactsRoot = installed.artifactsRoot,
                worldId = WorldId(1),
                onSelectedImportInputs = { inputs ->
                    if (observed != null || inputs.parsedImporterOptions != options.filterKeys { it in importerOptions }) {
                        throw SelectedSourceUnavailable()
                    }
                    observed = consumer.verifyBeforeWriteDetailed(inputs, installed.originalOp,
                        installed.typedTargetFingerprint, installed.appSourceSha, installed.imagePins)
                },
            )
            if (!bootstrap.ensureSeeded(installed.jdbc)) throw SelectedSourceUnavailable()
            val selected = observed ?: throw SelectedSourceUnavailable()
            val dataSource = installed.jdbc.dataSource ?: throw SelectedSourceUnavailable()
            val caps = D101SeedCapPromotionGate(dataSource).observeNewWorldBeforePromotion()
            if (caps.worldId != 1 || caps.scenarioCode != "scenario_3190" ||
                caps.configMaxGeneral != 50 || caps.gameEnvMaxGeneral != 50) throw SelectedSourceUnavailable()
            val result = sortedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "kind" to "D101_SEED_ONLY_RESULT_V1",
                "originalOp" to installed.originalOp,
                "typedTargetFingerprint" to installed.typedTargetFingerprint,
                "appSourceSha" to installed.appSourceSha,
                "imagePins" to installed.imagePins.toSortedMap(),
                "selectedSourceReceiptSha256" to selected.selectedSourceReceiptSha256,
                "scenarioRawSha256" to selected.counts.scenarioRawSha256,
                "scenarioRawByteLength" to selected.counts.scenarioRawByteLength,
                "effectiveOptions" to selected.effectiveOptions.toSortedMap(),
                "optionProvenance" to selected.optionProvenance.toSortedMap(),
                "activeGeneralRows" to selected.counts.activeGeneralRows,
                "activeRetainerRows" to selected.counts.activeRetainerRows,
                "configMaxGeneral" to caps.configMaxGeneral,
                "gameEnvMaxGeneral" to caps.gameEnvMaxGeneral,
                "candidateConfigOriginalSha256" to caps.configOriginalSha256,
                "candidateMetaOriginalSha256" to caps.metaOriginalSha256,
                "candidateGameEnvOriginalSha256" to caps.gameEnvOriginalSha256,
                "observedGeneration" to caps.generation,
                "observedAtUtc" to caps.observedAtUtc,
            )
            out.println("D101_SEED_ONLY_RESULT_V1\t" + mapper.writeValueAsString(result))
            if (out.checkError()) throw SelectedSourceUnavailable()
            0
        }
    } catch (_: ServiceConfigurationError) {
        78
    } catch (_: Exception) {
        // Never print DSN, native paths, receipts or arbitrary exception messages to the worker log.
        78
    }
}
