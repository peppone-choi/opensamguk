package opensamguk.engine.baseline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import opensamguk.engine.boot.SeedBootstrap
import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.RecordingState

private const val RAW_SCHEMA_VERSION = "cqrs-runtime-baseline.raw.v2"
private const val FIXTURE_SCHEMA_VERSION = "cqrs-baseline-fixture.v1"
private const val SCENARIO_CODE = "scenario_1010"
private const val FIXED_HOT_LOG_ROWS = 256
private const val LOG_PAYLOAD_CHARACTERS = 192
private const val REQUIRED_CGROUP_BYTES = 2L * 1024L * 1024L * 1024L
private const val DEFAULT_BASE_ROWS = 10_000

object CqrsBaselineMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val config = BaselineRunConfig.parse(args)
        Files.createDirectories(config.output.parent)
        Files.createDirectories(config.jfr.parent)

        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.name = "cqrs-runtime-baseline-${config.profile.wireName}"
            recording.setToDisk(true)
            recording.start()
            try {
                writeJson(config.output, execute(config))
            } finally {
                if (recording.state == RecordingState.RUNNING) recording.stop()
                recording.dump(config.jfr)
            }
        }
    }

    private fun execute(config: BaselineRunConfig): Map<String, Any> {
        val jvm = JvmInfo.read()
        val cgroup = CgroupInfo.read()
        validateProbe(jvm, cgroup)

        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = requiredEnvironment("BASELINE_DB_URL")
            username = requiredEnvironment("BASELINE_DB_USERNAME")
            password = requiredEnvironment("BASELINE_DB_PASSWORD")
        }
        val jdbc = JdbcTemplate(dataSource)
        val named = NamedParameterJdbcTemplate(dataSource)

        val bootStartedAt = System.nanoTime()
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()

        val bootstrap = SeedBootstrap(SCENARIO_CODE)
        check(bootstrap.ensureSeeded(jdbc)) { "Expected a fresh baseline database to seed $SCENARIO_CODE" }
        val fixture = insertFixture(jdbc, config)
        val loader = WorldSnapshotLoader(jdbc, bootstrap)
        val snapshotStartedAt = System.nanoTime()
        val snapshot = loader.buildSnapshot()
        val snapshotDurationMs = elapsedMillis(snapshotStartedAt)
        val world = InMemoryTurnWorld(snapshot)
        val bootDurationMs = elapsedMillis(bootStartedAt)

        val registry = CommandRegistry(GeneralActionPipeline())
        val hiddenSeed = world.getState().meta["hiddenSeed"] as String
        val startYear = (world.getState().meta["startYear"] as Number).toInt()
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear)
        val reservedTurns = ReservedTurnRepository(named)
        val lifecycle = TurnDaemonLifecycle(world, handler) { generalId ->
            reservedTurns.readReserved(generalId, 0)
        }
        val runTime = world.listGenerals().maxOf { it.turnTime }.plus(1, ChronoUnit.SECONDS)
        val dueCount = lifecycle.dueGenerals(runTime).size
        check(dueCount > 0) { "The seeded scenario must have at least one due general" }
        val tickStartedAt = System.nanoTime()
        val handled = lifecycle.runTick(runTime)
        val tickDurationMs = elapsedMillis(tickStartedAt)
        check(handled.isNotEmpty()) { "The representative tick must handle the seeded due generals" }
        check(handled.all { it.definition.key == "휴식" }) { "Scenario seed fixture must resolve only 휴식" }

        val heapBeforeGc = HeapSnapshot.read()
        val rssBeforeGc = residentSetBytes()
        val gcBefore = GcSnapshot.read()
        ManagementFactory.getMemoryMXBean().gc()
        Thread.sleep(200)
        ManagementFactory.getMemoryMXBean().gc()
        Thread.sleep(200)
        val heapAfterGc = HeapSnapshot.read()
        val rssAfterGc = residentSetBytes()
        val gcAfter = GcSnapshot.read()
        check(gcAfter.count > gcBefore.count) {
            "Explicit MemoryMXBean.gc() did not trigger a collection; retained-heap proxy is invalid"
        }

        val databaseRows = databaseRowCounts(jdbc)
        val snapshotRows = linkedMapOf<String, Long>(
            "generals" to snapshot.generals.size.toLong(),
            "cities" to snapshot.cities.size.toLong(),
            "nations" to snapshot.nations.size.toLong(),
            "diplomacy" to snapshot.diplomacy.size.toLong(),
            "accessLogs" to snapshot.accessLogs.size.toLong(),
            "globalLogs" to ((snapshot.state.meta["globalLogs"] as? List<*>)?.size?.toLong() ?: 0L),
            "nationHistoryEntries" to ((snapshot.state.meta["nationHistory"] as? Map<*, *>)
                ?.values
                ?.sumOf { (it as? List<*>)?.size?.toLong() ?: 0L } ?: 0L),
            "generalHistoryEntries" to ((snapshot.state.meta["generalHistory"] as? Map<*, *>)
                ?.values
                ?.sumOf { (it as? List<*>)?.size?.toLong() ?: 0L } ?: 0L),
        )

        return linkedMapOf(
            "schemaVersion" to RAW_SCHEMA_VERSION,
            "profile" to config.profile.wireName,
            "fixture" to fixture.asMap(),
            "jvm" to jvm.asMap(),
            "cgroup" to cgroup.asMap(),
            "memory" to linkedMapOf(
                "rssBeforeGcBytes" to rssBeforeGc,
                "rssAfterGcBytes" to rssAfterGc,
                "heapBeforeGc" to heapBeforeGc.asMap(),
                "heapAfterGc" to heapAfterGc.asMap(),
            ),
            "gc" to linkedMapOf(
                "before" to gcBefore.asMap(),
                "after" to gcAfter.asMap(),
                "countDelta" to (gcAfter.count - gcBefore.count),
                "collectionTimeDeltaMillis" to (gcAfter.timeMillis - gcBefore.timeMillis),
            ),
            "durations" to linkedMapOf(
                "bootDurationMs" to bootDurationMs,
                "snapshotDurationMs" to snapshotDurationMs,
                "tickDurationMs" to tickDurationMs,
            ),
            "tick" to linkedMapOf(
                "dueCount" to dueCount,
                "handledCount" to handled.size,
            ),
            "rows" to linkedMapOf(
                "database" to databaseRows,
                "snapshot" to snapshotRows,
            ),
            "artifacts" to linkedMapOf(
                "jfrFile" to config.jfr.fileName.toString(),
                "jfrConfiguration" to "profile",
            ),
            "images" to linkedMapOf(
                "probeTag" to optionalEnvironment("BASELINE_PROBE_IMAGE_TAG"),
                "probeId" to optionalEnvironment("BASELINE_PROBE_IMAGE_ID"),
                "postgresTag" to optionalEnvironment("BASELINE_POSTGRES_IMAGE_TAG"),
                "postgresId" to optionalEnvironment("BASELINE_POSTGRES_IMAGE_ID"),
            ),
        )
    }

    private fun insertFixture(jdbc: JdbcTemplate, config: BaselineRunConfig): FixtureDescriptor {
        val coldHistoryRows = Math.multiplyExact(config.baseRows, config.profile.coldHistoryMultiplier)
        jdbc.update(
            """
            INSERT INTO log_entry (scope, category, year, month, text, meta)
            SELECT CAST('SYSTEM' AS log_scope), CAST('ACTION' AS log_category), 184, 1,
                   'cqrs-baseline-hot-v1-' || lpad(series::text, 8, '0') || ':' || repeat('H', $LOG_PAYLOAD_CHARACTERS),
                   '{}'::jsonb
              FROM generate_series(1, ?) AS series
            """.trimIndent(),
            FIXED_HOT_LOG_ROWS,
        )
        jdbc.update(
            """
            INSERT INTO log_entry (scope, category, year, month, text, meta)
            SELECT CAST('SYSTEM' AS log_scope), CAST('HISTORY' AS log_category), 184, 1,
                   'cqrs-baseline-cold-v1-' || lpad(series::text, 10, '0') || ':' || repeat('C', $LOG_PAYLOAD_CHARACTERS),
                   '{}'::jsonb
              FROM generate_series(1, ?) AS series
            """.trimIndent(),
            coldHistoryRows,
        )
        return FixtureDescriptor(
            profile = config.profile,
            baseRows = config.baseRows,
            coldHistoryRows = coldHistoryRows,
            fixedHotLogRows = FIXED_HOT_LOG_ROWS,
            fixtureSha256 = fixtureSha256(config.profile, config.baseRows, coldHistoryRows),
        )
    }

    private fun databaseRowCounts(jdbc: JdbcTemplate): Map<String, Long> = linkedMapOf<String, Long>().apply {
        for (table in listOf("world_state", "general", "city", "nation", "diplomacy", "rank_data", "log_entry")) {
            this[table] = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L
        }
    }

    private fun fixtureSha256(profile: BaselineProfile, baseRows: Int, coldHistoryRows: Int): String {
        val manifest = listOf(
            "schema=$FIXTURE_SCHEMA_VERSION",
            "scenario=$SCENARIO_CODE",
            "profile=${profile.wireName}",
            "baseRows=$baseRows",
            "coldHistoryRows=$coldHistoryRows",
            "fixedHotLogRows=$FIXED_HOT_LOG_ROWS",
            "logPayloadCharacters=$LOG_PAYLOAD_CHARACTERS",
            "fixture=synthetic-scenario-seed-proxy",
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(manifest.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun validateProbe(jvm: JvmInfo, cgroup: CgroupInfo) {
        require(Runtime.version().feature() == 21) { "Baseline probe requires JDK 21, found ${Runtime.version()}" }
        require(cgroup.memoryLimitBytes == REQUIRED_CGROUP_BYTES) {
            "Baseline probe requires an exact 2 GiB cgroup, found ${cgroup.memoryLimitBytes} bytes"
        }
        for (requiredArgument in listOf(
            "-XX:+UseG1GC",
            "-XX:MaxRAMPercentage=60",
            "-XX:InitialRAMPercentage=40",
        )) {
            require(jvm.inputArguments.contains(requiredArgument)) {
                "Baseline probe is missing required JVM argument $requiredArgument; args=${jvm.inputArguments}"
            }
        }
        require(jvm.gcNames.any { it.contains("G1") }) { "Baseline probe requires G1GC, found ${jvm.gcNames}" }
        val maxHeapRatio = jvm.maxHeapBytes.toDouble() / cgroup.memoryLimitBytes.toDouble()
        require(maxHeapRatio in 0.55..0.65) {
            "Expected MaxRAMPercentage=60 to yield a heap near 60% of cgroup; ratio=$maxHeapRatio"
        }
    }

    private fun writeJson(path: Path, content: Map<String, Any>) {
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        val json = ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValueAsString(content)
        Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: error("Missing required environment variable $name")

    private fun optionalEnvironment(name: String): String? = System.getenv(name)?.takeIf(String::isNotBlank)

    private fun residentSetBytes(): Long {
        val line = Files.readAllLines(Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("VmRSS:") }
            ?: error("/proc/self/status did not provide VmRSS; run the probe inside Linux Docker")
        val kibibytes = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
            ?: error("Unable to parse VmRSS line: $line")
        return kibibytes * 1024L
    }
}

private data class BaselineRunConfig(
    val profile: BaselineProfile,
    val baseRows: Int,
    val output: Path,
    val jfr: Path,
) {
    companion object {
        fun parse(args: Array<String>): BaselineRunConfig {
            val options = args.associate { argument ->
                require(argument.startsWith("--") && argument.contains('=')) {
                    "Expected --key=value arguments, found '$argument'"
                }
                val separator = argument.indexOf('=')
                argument.substring(2, separator) to argument.substring(separator + 1)
            }
            val baseRows = options["base-rows"]?.toIntOrNull() ?: DEFAULT_BASE_ROWS
            require(baseRows > 0) { "base-rows must be positive" }
            return BaselineRunConfig(
                profile = BaselineProfile.parse(requiredOption(options, "profile")),
                baseRows = baseRows,
                output = Path.of(requiredOption(options, "output")).toAbsolutePath().normalize(),
                jfr = Path.of(requiredOption(options, "jfr")).toAbsolutePath().normalize(),
            )
        }

        private fun requiredOption(options: Map<String, String>, key: String): String =
            options[key]?.takeIf(String::isNotBlank) ?: error("Missing required --$key option")
    }
}

private enum class BaselineProfile(
    val wireName: String,
    val coldHistoryMultiplier: Int,
) {
    CURRENT("current", 1),
    COLD_10X("cold10x", 10),
    ;

    companion object {
        fun parse(value: String): BaselineProfile = entries.firstOrNull { it.wireName == value }
            ?: error("Unsupported profile '$value'; expected current or cold10x")
    }
}

private data class FixtureDescriptor(
    val profile: BaselineProfile,
    val baseRows: Int,
    val coldHistoryRows: Int,
    val fixedHotLogRows: Int,
    val fixtureSha256: String,
) {
    fun asMap(): Map<String, Any> = linkedMapOf(
        "kind" to "synthetic-scenario-seed-proxy",
        "label" to "synthetic production-shaped seed proxy; not production data or a sanitized production shape",
        "scenario" to SCENARIO_CODE,
        "profile" to profile.wireName,
        "baseRows" to baseRows,
        "coldHistoryMultiplier" to profile.coldHistoryMultiplier,
        "coldHistoryRows" to coldHistoryRows,
        "fixedHotLogRows" to fixedHotLogRows,
        "logPayloadCharacters" to LOG_PAYLOAD_CHARACTERS,
        "sha256" to fixtureSha256,
    )
}

private data class JvmInfo(
    val version: String,
    val vmName: String,
    val inputArguments: List<String>,
    val maxHeapBytes: Long,
    val gcNames: List<String>,
) {
    fun asMap(): Map<String, Any> = linkedMapOf(
        "version" to version,
        "vmName" to vmName,
        "inputArguments" to inputArguments,
        "maxHeapBytes" to maxHeapBytes,
        "gcNames" to gcNames,
    )

    companion object {
        fun read(): JvmInfo = JvmInfo(
            version = Runtime.version().toString(),
            vmName = System.getProperty("java.vm.name"),
            inputArguments = ManagementFactory.getRuntimeMXBean().inputArguments.toList(),
            maxHeapBytes = Runtime.getRuntime().maxMemory(),
            gcNames = ManagementFactory.getGarbageCollectorMXBeans().map(GarbageCollectorMXBean::getName),
        )
    }
}

private data class CgroupInfo(
    val memoryLimitBytes: Long,
    val memoryCurrentBytes: Long?,
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "memoryLimitBytes" to memoryLimitBytes,
        "memoryCurrentBytes" to memoryCurrentBytes,
    )

    companion object {
        fun read(): CgroupInfo {
            val v2Limit = readNumber(Path.of("/sys/fs/cgroup/memory.max"))
            val v1Limit = readNumber(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"))
            val limit = v2Limit ?: v1Limit
                ?: error("No finite cgroup memory limit found; run the probe inside the Docker cgroup")
            val current = readNumber(Path.of("/sys/fs/cgroup/memory.current"))
                ?: readNumber(Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"))
            return CgroupInfo(memoryLimitBytes = limit, memoryCurrentBytes = current)
        }

        private fun readNumber(path: Path): Long? {
            if (!Files.isRegularFile(path)) return null
            return Files.readString(path).trim().takeIf { it != "max" }?.toLongOrNull()
        }
    }
}

private data class HeapSnapshot(
    val usedBytes: Long,
    val committedBytes: Long,
    val maxBytes: Long,
) {
    fun asMap(): Map<String, Long> = linkedMapOf(
        "usedBytes" to usedBytes,
        "committedBytes" to committedBytes,
        "maxBytes" to maxBytes,
    )

    companion object {
        fun read(): HeapSnapshot = ManagementFactory.getMemoryMXBean().heapMemoryUsage.let { usage ->
            HeapSnapshot(usage.used, usage.committed, usage.max)
        }
    }
}

private data class GcSnapshot(
    val count: Long,
    val timeMillis: Long,
    val collectors: Map<String, Map<String, Long>>,
) {
    fun asMap(): Map<String, Any> = linkedMapOf(
        "count" to count,
        "timeMillis" to timeMillis,
        "collectors" to collectors,
    )

    companion object {
        fun read(): GcSnapshot {
            val collectors = linkedMapOf<String, Map<String, Long>>()
            for (collector in ManagementFactory.getGarbageCollectorMXBeans()) {
                collectors[collector.name] = linkedMapOf(
                    "count" to collector.collectionCount.coerceAtLeast(0),
                    "timeMillis" to collector.collectionTime.coerceAtLeast(0),
                )
            }
            return GcSnapshot(
                count = collectors.values.sumOf { it.getValue("count") },
                timeMillis = collectors.values.sumOf { it.getValue("timeMillis") },
                collectors = collectors,
            )
        }
    }
}
