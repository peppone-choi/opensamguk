package opensamguk.engine.baseline

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import opensamguk.engine.boot.SeedBootstrap
import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.WorldSnapshot
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
internal const val LOCAL_SANITIZED_AGGREGATE_RAW_SCHEMA_VERSION = "cqrs-runtime-baseline.raw.local-sanitized-aggregate.v1"
private const val FIXTURE_SCHEMA_VERSION = "cqrs-baseline-fixture.v2"
private const val PRODUCTION_FIXTURE_CONFIG_SCHEMA_VERSION = "cqrs-runtime-baseline.fixture-config.v3"
private const val SANITIZED_PRODUCTION_FIXTURE_KIND = "sanitized-production-shape"
internal const val LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_SCHEMA_VERSION = "cqrs-runtime-baseline.local-sanitized-aggregate-config.v1"
internal const val LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND = "local-sanitized-aggregate-surrogate"
private const val PAYLOAD_BYTE_SEMANTICS = "selected-loader-fields-postgres-text-bytes.v1"
private const val LOADER_INPUT_INVENTORY_SCHEMA_VERSION = "cqrs-loader-input-inventory.v2"
private const val SCENARIO_CODE = "scenario_1010"
private const val BASELINE_FIXED_INSTANT = "0184-01-01T00:00:00Z"
private const val BASELINE_FIXED_SERVER_ID = "opensamguk_baseline_1010"
private const val FIXED_HOT_LOG_ROWS = 256
private const val LOG_PAYLOAD_CHARACTERS = 192
private const val MAX_PRODUCTION_PAYLOAD_BYTES = 1024 * 1024
private const val MAX_PRODUCTION_DIMENSION = 2_147_483_647
private const val REQUIRED_CGROUP_BYTES = 2L * 1024L * 1024L * 1024L
private const val DEFAULT_BASE_ROWS = 10_000
private val productionShapeTableToDatabaseTable = linkedMapOf(
    "worldState" to "world_state",
    "city" to "city",
    "nation" to "nation",
    "general" to "general",
    "diplomacy" to "diplomacy",
    "rankData" to "rank_data",
    "logEntry" to "log_entry",
)
private val productionShapeSnapshotFields = listOf(
    "generals",
    "cities",
    "nations",
    "diplomacy",
    "accessLogs",
    "globalLogs",
    "nationHistoryEntries",
    "generalHistoryEntries",
)

object CqrsBaselineMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (validateNgGamesObservationOnly(args)) return
        if (validateFixtureConfigOnly(args)) return
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

    private fun validateNgGamesObservationOnly(args: Array<String>): Boolean {
        val observationArgument = args.singleOrNull { it.startsWith("--validate-ng-games-observation=") } ?: return false
        require(args.size == 1) {
            "ngGames observation validation requires exactly --validate-ng-games-observation"
        }
        val values = observationArgument.substringAfter('=').split(':')
        require(values.size == 4) {
            "ngGames observation validation requires sourceRows:countPayloadBytes:activeRows:activePayloadBytes"
        }
        val numbers = values.mapIndexed { index, value ->
            value.toLongOrNull()?.takeIf { it >= 0L }
                ?: error("ngGames observation validation value $index must be a non-negative integer")
        }
        val activeGame = when (numbers[2]) {
            0L -> {
                require(numbers[3] == 0L) {
                    "ngGames observation validation has active payload bytes without an active game"
                }
                null
            }
            1L -> SourceAggregate(sourceRows = 1L, payloadBytes = numbers[3])
            else -> error("ngGames observation validation activeRows must be 0 or 1")
        }
        val metric = LoaderInputObservation.composeNgGamesObservation(
            fullCardinality = SourceAggregate(sourceRows = numbers[0], payloadBytes = numbers[1]),
            activeGame = activeGame,
        )
        println(
            "ngGames observation valid: sourceRows=${metric.sourceRows} retainedItems=${metric.retainedItems} " +
                "payloadBytes=${metric.payloadBytes}",
        )
        return true
    }

    private fun validateFixtureConfigOnly(args: Array<String>): Boolean {
        val fixtureArgument = args.singleOrNull { it.startsWith("--validate-fixture-config=") } ?: return false
        require(args.size == 2) {
            "Fixture-config validation requires exactly --profile and --validate-fixture-config"
        }
        val profileArgument = args.singleOrNull { it.startsWith("--profile=") }
            ?: error("Fixture-config validation requires --profile")
        val profile = BaselineProfile.parse(profileArgument.substringAfter('='))
        val fixturePath = Path.of(fixtureArgument.substringAfter('='))
            .toAbsolutePath()
            .normalize()
        ProductionShapeFixtureConfig.read(fixturePath, profile)
        println("Production-shape fixture config valid: profile=${profile.wireName}")
        return true
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

        val baselineWorldId = opensamguk.common.world.WorldId(1)
        val bootstrap = SeedBootstrap(scenarioCode = SCENARIO_CODE, worldId = baselineWorldId)
        val fixture = config.productionShapeFixture?.takeIf(ProductionShapeFixtureConfig::isLocalSanitizedAggregateSurrogate)
            ?.let { localFixture ->
                LocalSanitizedAggregateMaterializer(dataSource).materialize(localFixture)
                FixtureDescriptor.local(config.profile, config.baseRows, localFixture)
            }
            ?: run {
                check(bootstrap.ensureSeeded(jdbc)) { "Expected a fresh baseline database to seed $SCENARIO_CODE" }
                normalizeFixtureSeedClock(jdbc)
                insertFixture(jdbc, config)
            }
        val loader = WorldSnapshotLoader(jdbc, bootstrap, baselineWorldId)
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
            reservedTurns.readReserved(baselineWorldId, generalId, 0)
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
        val loaderInputs = observedLoaderInputMetrics(jdbc, snapshot)
        config.productionShapeFixture?.validateObservedShape(databaseRows, snapshotRows, loaderInputs)

        return linkedMapOf(
            "schemaVersion" to if (fixture.isLocalSanitizedAggregateSurrogate) {
                LOCAL_SANITIZED_AGGREGATE_RAW_SCHEMA_VERSION
            } else {
                RAW_SCHEMA_VERSION
            },
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
                "loaderInputs" to loaderInputs.mapValues { (_, metrics) -> metrics.asMap() },
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
        val productionShapeFixture = config.productionShapeFixture
        require(productionShapeFixture?.isLocalSanitizedAggregateSurrogate != true) {
            "Local sanitized aggregate fixtures must use the dedicated local materializer"
        }
        val coldHistoryRows = productionShapeFixture?.coldHistoryRows
            ?: Math.multiplyExact(config.baseRows, config.profile.coldHistoryMultiplier)
        val fixedHotLogRows = productionShapeFixture?.fixedHotActionRows ?: FIXED_HOT_LOG_ROWS
        val hotPayloadCharacters = productionShapeFixture?.payloadSizeBytes?.getValue("hotAction") ?: LOG_PAYLOAD_CHARACTERS
        val coldPayloadCharacters = productionShapeFixture?.payloadSizeBytes?.getValue("coldHistory") ?: LOG_PAYLOAD_CHARACTERS
        val hotPayloadExpression = "repeat('H', $hotPayloadCharacters)"
        val coldPayloadExpression = "repeat('C', $coldPayloadCharacters)"
        jdbc.update(
            """
            INSERT INTO log_entry (scope, category, year, month, text, meta)
            SELECT CAST('SYSTEM' AS log_scope), CAST('ACTION' AS log_category), 184, 1,
                   $hotPayloadExpression,
                   '{}'::jsonb
              FROM generate_series(1, ?) AS series
            """.trimIndent(),
            fixedHotLogRows,
        )
        jdbc.update(
            """
            INSERT INTO log_entry (scope, category, year, month, text, meta)
            SELECT CAST('SYSTEM' AS log_scope), CAST('HISTORY' AS log_category), 184, 1,
                   $coldPayloadExpression,
                   '{}'::jsonb
              FROM generate_series(1, ?) AS series
            """.trimIndent(),
            coldHistoryRows,
        )
        return FixtureDescriptor(
            profile = config.profile,
            baseRows = config.baseRows,
            coldHistoryRows = coldHistoryRows,
            fixedHotLogRows = fixedHotLogRows,
            productionShapeFixture = productionShapeFixture,
            fixtureSha256 = productionShapeFixture?.let {
                productionFixtureSha256(config.profile, config.baseRows, it)
            } ?: fixtureSha256(config.profile, config.baseRows, coldHistoryRows),
        )
    }

    private fun databaseRowCounts(jdbc: JdbcTemplate): Map<String, Long> = linkedMapOf<String, Long>().apply {
        for (table in listOf("world_state", "general", "city", "nation", "diplomacy", "rank_data", "log_entry")) {
            this[table] = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0L
        }
    }

    private fun normalizeFixtureSeedClock(jdbc: JdbcTemplate) {
        jdbc.update(
            """
            UPDATE general
               SET turn_time = TIMESTAMPTZ '$BASELINE_FIXED_INSTANT' +
                               (turn_time - (SELECT start_time FROM world_state WHERE id = 1))
             WHERE world_id = 1
            """.trimIndent(),
        )
        jdbc.update(
            """
            UPDATE ng_games
               SET server_id = ?,
                   date = TIMESTAMPTZ '$BASELINE_FIXED_INSTANT',
                   env = jsonb_set(env, '{server_id}', to_jsonb(CAST(? AS text)), true)
            """.trimIndent(),
            BASELINE_FIXED_SERVER_ID,
            BASELINE_FIXED_SERVER_ID,
        )
        jdbc.update(
            """
            UPDATE world_state
               SET start_time = TIMESTAMPTZ '$BASELINE_FIXED_INSTANT',
                   meta = jsonb_set(
                       jsonb_set(
                           jsonb_set(meta, '{startTime}', to_jsonb(CAST(? AS text)), true),
                           '{serverId}', to_jsonb(CAST(? AS text)), true
                       ),
                       '{server_id}', to_jsonb(CAST(? AS text)), true
                   ),
                   config = jsonb_set(config, '{starttime}', to_jsonb(CAST(? AS text)), true)
             WHERE id = 1
            """.trimIndent(),
            BASELINE_FIXED_INSTANT,
            BASELINE_FIXED_SERVER_ID,
            BASELINE_FIXED_SERVER_ID,
            BASELINE_FIXED_INSTANT,
        )
    }

    private fun observedLoaderInputMetrics(
        jdbc: JdbcTemplate,
        snapshot: WorldSnapshot,
    ): Map<String, ObservedLoaderInputMetric> {
        val state = snapshot.state
        val worldId = snapshot.worldId.value
        val nationHistory = state.meta["nationHistory"] as? Map<*, *>
        val generalHistory = state.meta["generalHistory"] as? Map<*, *>
        val globalLogs = state.meta["globalLogs"] as? List<*>
        val statisticRows = state.meta["statisticRows"] as? List<*>
        val activeUniqueAuctionItems = state.meta["activeUniqueAuctionItems"] as? List<*>
        val storedUniqueItemCounts = state.meta["storedUniqueItemCounts"] as? Map<*, *>
        val inheritancePoints = state.meta["inheritancePoints"] as? Map<*, *>
        val archivedNationSource = snapshot.serverId?.let { serverId ->
            LoaderInputObservation.aggregate(jdbc, "archivedNationIds", serverId)
        } ?: SourceAggregate(0L, 0L)
        val values = linkedMapOf(
            "worldState" to LoaderInputObservation.aggregate(jdbc, "worldState", worldId).withRetainedItems(1L),
            "ngGames" to LoaderInputObservation.aggregateNgGames(jdbc, snapshot.serverId),
            "archivedNationIds" to archivedNationSource.withRetainedItems(snapshot.archivedNationIds.size.toLong()),
            "statistics" to LoaderInputObservation.aggregate(jdbc, "statistics")
                .withRetainedItems(statisticRows?.size?.toLong() ?: 0L),
            "nationHistoryLogs" to LoaderInputObservation.aggregate(jdbc, "nationHistoryLogs")
                .withRetainedItems(historyEntryCount(nationHistory)),
            "generalHistoryLogs" to LoaderInputObservation.aggregate(jdbc, "generalHistoryLogs")
                .withRetainedItems(historyEntryCount(generalHistory)),
            "systemActionLogs" to LoaderInputObservation.aggregate(jdbc, "systemActionLogs")
                .withRetainedItems(globalLogs?.count { (it as? Map<*, *>)?.get("category") == "ACTION" }?.toLong() ?: 0L),
            "systemHistoryLogs" to LoaderInputObservation.aggregate(jdbc, "systemHistoryLogs")
                .withRetainedItems(globalLogs?.count { (it as? Map<*, *>)?.get("category") == "HISTORY" }?.toLong() ?: 0L),
            "activeUniqueAuctionItems" to LoaderInputObservation.aggregate(jdbc, "activeUniqueAuctionItems")
                .withRetainedItems(activeUniqueAuctionItems?.size?.toLong() ?: 0L),
            "storedUniqueItemNamespaces" to LoaderInputObservation.aggregate(jdbc, "storedUniqueItemNamespaces")
                .withRetainedItems(storedUniqueItemCounts?.size?.toLong() ?: 0L),
            "gameEnv" to LoaderInputObservation.aggregate(jdbc, "gameEnv").withRetainedItems(scalarCount(
                jdbc,
                "SELECT count(DISTINCT key) FROM game_kv WHERE \"table\" = 'game_env' AND namespace IN ('', 'game_env')",
            )),
            "nationEnv" to LoaderInputObservation.aggregate(jdbc, "nationEnv")
                .withRetainedItems(retainedMapEntryCount(snapshot.nations.map { it.meta["nation_env"] })),
            "inheritancePoints" to LoaderInputObservation.aggregate(jdbc, "inheritancePoints")
                .withRetainedItems(retainedNestedMapEntryCount(listOf(inheritancePoints))),
            "generalRankValues" to LoaderInputObservation.aggregate(jdbc, "generalRankValues").withRetainedItems(scalarCount(
                jdbc,
                "SELECT count(*) FROM (SELECT DISTINCT r.general_id, r.type FROM rank_data r JOIN general g ON g.id = r.general_id) AS retained_rank_values",
            )),
            "nations" to LoaderInputObservation.aggregate(jdbc, "nations", worldId).withRetainedItems(snapshot.nations.size.toLong()),
            "cities" to LoaderInputObservation.aggregate(jdbc, "cities", worldId).withRetainedItems(snapshot.cities.size.toLong()),
            "generals" to LoaderInputObservation.aggregate(jdbc, "generals", worldId).withRetainedItems(snapshot.generals.size.toLong()),
            "diplomacy" to LoaderInputObservation.aggregate(jdbc, "diplomacy").withRetainedItems(snapshot.diplomacy.size.toLong()),
            "generalAccessLogs" to LoaderInputObservation.aggregate(jdbc, "generalAccessLogs")
                .withRetainedItems(snapshot.accessLogs.size.toLong()),
        )
        LoaderInputObservation.requireInventoryBinding()
        require(values.keys.toList() == LoaderInputInventory.current.ids) {
            "Baseline loader-input observations diverged from the checked-in loader inventory"
        }
        return values
    }

    private fun scalarCount(jdbc: JdbcTemplate, query: String, vararg arguments: Any?): Long =
        jdbc.query(query, { rs, _ -> rs.getLong(1) }, *arguments).single()

    private fun historyEntryCount(history: Map<*, *>?): Long =
        history?.values?.sumOf { (it as? List<*>)?.size?.toLong() ?: 0L } ?: 0L

    private fun retainedNestedMapEntryCount(values: List<Any?>): Long = values.sumOf { value ->
        (value as? Map<*, *>)?.values?.sumOf { (it as? Map<*, *>)?.size?.toLong() ?: 0L } ?: 0L
    }

    private fun retainedMapEntryCount(values: List<Any?>): Long = values.sumOf { value ->
        (value as? Map<*, *>)?.size?.toLong() ?: 0L
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
            "payloadByteSemantics=$PAYLOAD_BYTE_SEMANTICS",
            "fixture=synthetic-scenario-seed-proxy",
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(manifest.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun productionFixtureSha256(
        profile: BaselineProfile,
        baseRows: Int,
        fixture: ProductionShapeFixtureConfig,
    ): String {
        val manifest = listOf(
            "schema=$FIXTURE_SCHEMA_VERSION",
            "fixture=$SANITIZED_PRODUCTION_FIXTURE_KIND",
            "manifestSha256=${requireNotNull(fixture.manifestSha256)}",
            "loaderInputInventorySha256=${fixture.loaderInputInventorySha256}",
            "payloadByteSemantics=${fixture.payloadByteSemantics}",
            "profile=${profile.wireName}",
            "baseRows=$baseRows",
            "coldHistoryRows=${fixture.coldHistoryRows}",
            "fixedHotLogRows=${fixture.fixedHotActionRows}",
            "hotActionPayloadBytes=${fixture.payloadSizeBytes.getValue("hotAction")}",
            "coldHistoryPayloadBytes=${fixture.payloadSizeBytes.getValue("coldHistory")}",
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
    val productionShapeFixture: ProductionShapeFixtureConfig?,
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
            require(options.size == args.size) { "Baseline probe options must not repeat a key" }
            val profile = BaselineProfile.parse(requiredOption(options, "profile"))
            val productionShapeFixture = options["fixture-config"]?.let { configPath ->
                require("base-rows" !in options) {
                    "--base-rows cannot override a sanitized production-shape fixture config"
                }
                ProductionShapeFixtureConfig.read(Path.of(configPath).toAbsolutePath().normalize(), profile)
            }
            require(productionShapeFixture == null || productionShapeFixture.isLocalSanitizedAggregateSurrogate) {
                "sanitized production-shape capture is blocked: scenario_1010 seed proxy cannot materialize an approved sanitized shape; " +
                    "use --validate-fixture-config until a deterministic sanitized materializer or approved sanitized restore exists"
            }
            val baseRows = productionShapeFixture?.baseRows
                ?: (options["base-rows"]?.toIntOrNull() ?: DEFAULT_BASE_ROWS)
            require(baseRows > 0) { "base-rows must be positive" }
            return BaselineRunConfig(
                profile = profile,
                baseRows = baseRows,
                productionShapeFixture = productionShapeFixture,
                output = Path.of(requiredOption(options, "output")).toAbsolutePath().normalize(),
                jfr = Path.of(requiredOption(options, "jfr")).toAbsolutePath().normalize(),
            )
        }

        private fun requiredOption(options: Map<String, String>, key: String): String =
            options[key]?.takeIf(String::isNotBlank) ?: error("Missing required --$key option")
    }
}

internal enum class BaselineProfile(
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

internal data class ProductionShapeFixtureConfig(
    val kind: String,
    val manifestSha256: String?,
    val policyId: String?,
    val policySha256: String?,
    val loaderInputInventorySha256: String,
    val payloadByteSemantics: String,
    val profile: BaselineProfile,
    val fixedHotActionRows: Int,
    val coldHistoryRows: Int,
    val payloadSizeBytes: Map<String, Int>,
    val expectedTableCardinalities: Map<String, Int>,
    val expectedSnapshotCardinalities: Map<String, Int>,
    val expectedLoaderInputs: Map<String, ExpectedLoaderInputMetric>,
) {
    val baseRows: Int = coldHistoryRows / profile.coldHistoryMultiplier
    val isLocalSanitizedAggregateSurrogate: Boolean
        get() = kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND

    fun validateObservedShape(
        databaseRows: Map<String, Long>,
        snapshotRows: Map<String, Long>,
        loaderInputs: Map<String, ObservedLoaderInputMetric>,
    ) {
        for ((productionName, databaseName) in productionShapeTableToDatabaseTable) {
            require(databaseRows[databaseName] == expectedTableCardinalities.getValue(productionName).toLong()) {
                "Baseline fixture table cardinality did not match for $productionName"
            }
        }
        for (name in productionShapeSnapshotFields) {
            require(snapshotRows[name] == expectedSnapshotCardinalities.getValue(name).toLong()) {
                "Baseline fixture snapshot cardinality did not match for $name"
            }
        }
        for (inputId in LoaderInputInventory.current.ids) {
            require(loaderInputs[inputId]?.matches(expectedLoaderInputs.getValue(inputId)) == true) {
                "Baseline fixture loader input did not match for $inputId"
            }
        }
    }

    companion object {
        private val productionExpectedRootFields = setOf(
            "schemaVersion",
            "kind",
            "manifestSha256",
            "loaderInputInventorySha256",
            "payloadByteSemantics",
            "loaderInputObservation",
            "profile",
            "fixedHotActionRows",
            "coldHistoryRows",
            "payloadSizeBytes",
            "expectedTableCardinalities",
            "expectedSnapshotCardinalities",
            "expectedLoaderInputs",
        )
        private val localExpectedRootFields = setOf(
            "schemaVersion",
            "kind",
            "policyId",
            "policySha256",
            "loaderInputInventorySha256",
            "payloadByteSemantics",
            "loaderInputObservation",
            "profile",
            "fixedHotActionRows",
            "coldHistoryRows",
            "payloadSizeBytes",
            "expectedTableCardinalities",
            "expectedSnapshotCardinalities",
            "expectedLoaderInputs",
        )

        fun read(path: Path, expectedProfile: BaselineProfile): ProductionShapeFixtureConfig {
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path)) {
                "Sanitized production-shape fixture config must be a regular non-symlink file"
            }
            val root: JsonNode = try {
                ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(Files.readString(path, StandardCharsets.UTF_8))
                    ?: error("Sanitized production-shape fixture config was empty")
            } catch (exception: Exception) {
                throw IllegalArgumentException("Sanitized production-shape fixture config was not readable JSON", exception)
            }
            require(root.isObject) { "Baseline fixture config must be an object" }
            val schemaVersion = requiredText(root, "schemaVersion")
            val kind = requiredText(root, "kind")
            val isProductionShape = schemaVersion == PRODUCTION_FIXTURE_CONFIG_SCHEMA_VERSION && kind == SANITIZED_PRODUCTION_FIXTURE_KIND
            val isLocalSurrogate = schemaVersion == LOCAL_SANITIZED_AGGREGATE_FIXTURE_CONFIG_SCHEMA_VERSION &&
                kind == LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND
            require(isProductionShape || isLocalSurrogate) { "Baseline fixture config schema or kind is unsupported" }
            requireExactFields(
                root,
                if (isProductionShape) productionExpectedRootFields else localExpectedRootFields,
                "Baseline fixture config",
            )
            val profile = BaselineProfile.parse(requiredText(root, "profile"))
            require(profile == expectedProfile) {
                "Baseline fixture config profile did not match --profile"
            }
            val manifestSha256 = root.get("manifestSha256")?.let { requiredText(root, "manifestSha256") }
            val policyId = root.get("policyId")?.let { requiredText(root, "policyId") }
            val policySha256 = root.get("policySha256")?.let { requiredText(root, "policySha256") }
            if (isProductionShape) {
                require(manifestSha256?.matches(Regex("^[0-9a-f]{64}$")) == true) {
                    "Sanitized production-shape fixture config manifest SHA-256 is invalid"
                }
            } else {
                require(policyId?.matches(Regex("op123-local-sanitized-aggregate-v[0-9]+")) == true) {
                    "Local sanitized aggregate fixture config policy id is invalid"
                }
                require(policySha256?.matches(Regex("^[0-9a-f]{64}$")) == true) {
                    "Local sanitized aggregate fixture config policy SHA-256 is invalid"
                }
            }
            val loaderInputInventorySha256 = requiredText(root, "loaderInputInventorySha256")
            require(loaderInputInventorySha256 == LoaderInputInventory.current.sha256) {
                "Sanitized production-shape fixture config is not bound to the checked-in loader-input inventory"
            }
            val payloadByteSemantics = requiredText(root, "payloadByteSemantics")
            require(payloadByteSemantics == PAYLOAD_BYTE_SEMANTICS) {
                "Sanitized production-shape fixture config payload byte semantics are unsupported"
            }
            requireLoaderInputObservation(root)
            val fixedHotActionRows = requiredPositiveInt(root, "fixedHotActionRows")
            val coldHistoryRows = requiredPositiveInt(root, "coldHistoryRows")
            require(coldHistoryRows % profile.coldHistoryMultiplier == 0) {
                "Baseline fixture cold-history rows do not divide its profile multiplier"
            }
            val payloadSizeBytes = requirePositiveIntMap(
                root,
                "payloadSizeBytes",
                listOf("hotAction", "coldHistory"),
            )
            val expectedTableCardinalities = requireNonNegativeIntMap(
                root,
                "expectedTableCardinalities",
                productionShapeTableToDatabaseTable.keys.toList(),
            )
            val expectedSnapshotCardinalities = requireNonNegativeIntMap(
                root,
                "expectedSnapshotCardinalities",
                productionShapeSnapshotFields,
            )
            val expectedLoaderInputs = requireExpectedLoaderInputs(root, "expectedLoaderInputs")
            val requiredLogEntryRows = Math.addExact(fixedHotActionRows, coldHistoryRows)
            require(expectedTableCardinalities.getValue("logEntry") >= requiredLogEntryRows) {
                "Baseline fixture log-entry cardinality is below its fixture inserts"
            }
            return ProductionShapeFixtureConfig(
                kind = kind,
                manifestSha256 = manifestSha256,
                policyId = policyId,
                policySha256 = policySha256,
                loaderInputInventorySha256 = loaderInputInventorySha256,
                payloadByteSemantics = payloadByteSemantics,
                profile = profile,
                fixedHotActionRows = fixedHotActionRows,
                coldHistoryRows = coldHistoryRows,
                payloadSizeBytes = payloadSizeBytes,
                expectedTableCardinalities = expectedTableCardinalities,
                expectedSnapshotCardinalities = expectedSnapshotCardinalities,
                expectedLoaderInputs = expectedLoaderInputs,
            )
        }

        private fun requireExactFields(node: JsonNode, expected: Set<String>, label: String) {
            val actual = node.fieldNames().asSequence().toSet()
            require(actual == expected) { "$label has an unexpected field set" }
        }

        private fun requiredText(node: JsonNode, field: String): String {
            val value = node.get(field)
            require(value != null && value.isTextual && value.textValue().isNotBlank()) {
                "Sanitized production-shape fixture config $field must be a non-empty string"
            }
            return value.textValue()
        }

        private fun requireLoaderInputObservation(node: JsonNode) {
            val value = requiredObject(node, "loaderInputObservation")
            val expected = ObjectMapper().valueToTree<JsonNode>(LoaderInputInventory.current.observationContract)
            require(value == expected) {
                "Sanitized production-shape fixture config loader-input observation diverges from the checked-in inventory"
            }
        }

        private fun requiredPositiveInt(node: JsonNode, field: String): Int {
            val value = node.get(field)
            require(value != null && value.isIntegralNumber && value.canConvertToInt() && value.intValue() > 0) {
                "Sanitized production-shape fixture config $field must be a positive integer"
            }
            return value.intValue()
        }

        private fun requirePositiveIntMap(node: JsonNode, field: String, expectedKeys: List<String>): Map<String, Int> {
            val value = requiredObject(node, field)
            requireExactFields(value, expectedKeys.toSet(), "Sanitized production-shape fixture config $field")
            return linkedMapOf<String, Int>().apply {
                for (key in expectedKeys) {
                    val number = value.get(key)
                    require(
                        number != null && number.isIntegralNumber && number.canConvertToInt()
                            && number.intValue() in 1..MAX_PRODUCTION_PAYLOAD_BYTES
                    ) {
                        "Sanitized production-shape fixture config $field.$key must be a positive integer"
                    }
                    this[key] = number.intValue()
                }
            }
        }

        private fun requireNonNegativeIntMap(
            node: JsonNode,
            field: String,
            expectedKeys: List<String>,
        ): Map<String, Int> {
            val value = requiredObject(node, field)
            requireExactFields(value, expectedKeys.toSet(), "Sanitized production-shape fixture config $field")
            return linkedMapOf<String, Int>().apply {
                for (key in expectedKeys) {
                    val number = value.get(key)
                    require(
                        number != null && number.isIntegralNumber && number.canConvertToInt()
                            && number.intValue() in 0..MAX_PRODUCTION_DIMENSION
                    ) {
                        "Sanitized production-shape fixture config $field.$key must be a non-negative integer"
                    }
                    this[key] = number.intValue()
                }
            }
        }

        private fun requireExpectedLoaderInputs(
            node: JsonNode,
            field: String,
        ): Map<String, ExpectedLoaderInputMetric> {
            val value = requiredObject(node, field)
            requireExactFields(value, LoaderInputInventory.current.ids.toSet(), "Sanitized production-shape fixture config $field")
            return linkedMapOf<String, ExpectedLoaderInputMetric>().apply {
                for (inputId in LoaderInputInventory.current.ids) {
                    val metrics = value.get(inputId)
                    require(metrics != null && metrics.isObject) {
                        "Sanitized production-shape fixture config $field.$inputId must be an object"
                    }
                    requireExactFields(
                        metrics,
                        setOf("sourceRows", "retainedItems", "payloadBytes"),
                        "Sanitized production-shape fixture config $field.$inputId",
                    )
                    this[inputId] = ExpectedLoaderInputMetric(
                        sourceRows = requiredNonNegativeInt(metrics, "sourceRows", "$field.$inputId"),
                        retainedItems = requiredNonNegativeInt(metrics, "retainedItems", "$field.$inputId"),
                        payloadBytes = requiredNonNegativeInt(metrics, "payloadBytes", "$field.$inputId"),
                    )
                }
            }
        }

        private fun requiredNonNegativeInt(node: JsonNode, field: String, label: String): Int {
            val value = node.get(field)
            require(
                value != null && value.isIntegralNumber && value.canConvertToInt()
                    && value.intValue() in 0..MAX_PRODUCTION_DIMENSION
            ) {
                "Sanitized production-shape fixture config $label.$field must be a non-negative integer"
            }
            return value.intValue()
        }

        private fun requiredObject(node: JsonNode, field: String): JsonNode {
            val value = node.get(field)
            require(value != null && value.isObject) {
                "Sanitized production-shape fixture config $field must be an object"
            }
            return value
        }
    }
}

internal data class ExpectedLoaderInputMetric(
    val sourceRows: Int,
    val retainedItems: Int,
    val payloadBytes: Int,
)

internal data class ObservedLoaderInputMetric(
    val sourceRows: Long,
    val retainedItems: Long,
    val payloadBytes: Long,
) {
    fun matches(expected: ExpectedLoaderInputMetric): Boolean =
        sourceRows == expected.sourceRows.toLong()
            && retainedItems == expected.retainedItems.toLong()
            && payloadBytes == expected.payloadBytes.toLong()

    fun asMap(): Map<String, Long> = linkedMapOf(
        "sourceRows" to sourceRows,
        "retainedItems" to retainedItems,
        "payloadBytes" to payloadBytes,
    )
}

private data class SourceAggregate(
    val sourceRows: Long,
    val payloadBytes: Long,
) {
    fun withRetainedItems(retainedItems: Long): ObservedLoaderInputMetric =
        ObservedLoaderInputMetric(sourceRows, retainedItems, payloadBytes)
}

private data class LoaderInputObservationDefinition(
    val id: String,
    val sourceQuery: String,
    val loaderColumns: List<String>,
    val payloadColumns: List<String>,
    val countQuery: String? = null,
    val inventorySource: String? = null,
    val inventoryRetainedAs: String? = null,
)

private object LoaderInputObservation {
    private const val NG_GAMES_SERVER_COUNT_COLUMN = "server_count"
    private const val NG_GAMES_COUNT_QUERY =
        "SELECT count(*) AS source_rows, octet_length((count(*))::text) AS payload_bytes FROM ng_games"
    private const val NG_GAMES_ACTIVE_QUERY =
        "SELECT id, server_id, season, scenario, scenario_name, map, CAST(env AS VARCHAR) AS env FROM ng_games WHERE server_id = ?"
    private val NG_GAMES_INVENTORY_SOURCE =
        "countQuery=$NG_GAMES_COUNT_QUERY | activeQuery=$NG_GAMES_ACTIVE_QUERY"
    private const val NG_GAMES_INVENTORY_RETAINED_AS = "state.meta.serverCount plus optional active game"
    private const val WORLD_STATE_INVENTORY_SOURCE = "world_state WHERE id = ?"
    private const val NATION_INVENTORY_SOURCE = "nation WHERE world_id = ?"
    private const val CITY_INVENTORY_SOURCE = "city WHERE world_id = ?"
    private const val GENERAL_INVENTORY_SOURCE = "general WHERE world_id = ?"

    private val definitions = listOf(
        LoaderInputObservationDefinition(
            "worldState",
            "SELECT id, current_year, current_month, current_phase, tick_seconds, isunited, status, meta, config, start_time, world_version, writer_epoch FROM world_state WHERE id = ?",
            listOf("id", "current_year", "current_month", "current_phase", "tick_seconds", "isunited", "status", "meta", "config", "start_time", "world_version", "writer_epoch"),
            listOf("id", "current_year", "current_month", "current_phase", "tick_seconds", "isunited", "status", "meta", "config", "start_time", "world_version", "writer_epoch"),
            inventorySource = WORLD_STATE_INVENTORY_SOURCE,
        ),
        LoaderInputObservationDefinition(
            "ngGames",
            NG_GAMES_ACTIVE_QUERY,
            listOf(NG_GAMES_SERVER_COUNT_COLUMN, "id", "server_id", "season", "scenario", "scenario_name", "map", "env"),
            listOf(NG_GAMES_SERVER_COUNT_COLUMN, "id", "server_id", "season", "scenario", "scenario_name", "map", "env"),
            countQuery = NG_GAMES_COUNT_QUERY,
            inventorySource = NG_GAMES_INVENTORY_SOURCE,
            inventoryRetainedAs = NG_GAMES_INVENTORY_RETAINED_AS,
        ),
        LoaderInputObservationDefinition(
            "archivedNationIds",
            "SELECT nation FROM ng_old_nations WHERE server_id = ? ORDER BY nation ASC",
            listOf("nation"),
            listOf("nation"),
        ),
        LoaderInputObservationDefinition(
            "statistics",
            "SELECT id, nation_count, nation_name, nation_hist, gen_count, personal_hist, special_hist, CAST(aux AS VARCHAR) AS aux FROM statistic ORDER BY id ASC",
            listOf("id", "nation_count", "nation_name", "nation_hist", "gen_count", "personal_hist", "special_hist", "aux"),
            listOf("id", "nation_count", "nation_name", "nation_hist", "gen_count", "personal_hist", "special_hist", "aux"),
        ),
        LoaderInputObservationDefinition(
            "nationHistoryLogs",
            "SELECT nation_id, text FROM log_entry WHERE scope = 'NATION' AND category = 'HISTORY' AND nation_id IS NOT NULL ORDER BY nation_id ASC, id DESC",
            listOf("nation_id", "text"),
            listOf("text"),
        ),
        LoaderInputObservationDefinition(
            "generalHistoryLogs",
            "SELECT general_id, text FROM log_entry WHERE scope = 'GENERAL' AND category = 'HISTORY' AND general_id IS NOT NULL ORDER BY general_id ASC, id DESC",
            listOf("general_id", "text"),
            listOf("text"),
        ),
        LoaderInputObservationDefinition(
            "systemActionLogs",
            "SELECT category, year, month, text FROM log_entry WHERE scope = 'SYSTEM' AND category = 'ACTION' ORDER BY id DESC",
            listOf("category", "year", "month", "text"),
            listOf("text"),
        ),
        LoaderInputObservationDefinition(
            "systemHistoryLogs",
            "SELECT category, year, month, text FROM log_entry WHERE scope = 'SYSTEM' AND category = 'HISTORY' ORDER BY id DESC",
            listOf("category", "year", "month", "text"),
            listOf("text"),
        ),
        LoaderInputObservationDefinition(
            "activeUniqueAuctionItems",
            "SELECT target FROM ng_auction WHERE type = 'uniqueItem' AND finished = false ORDER BY id ASC",
            listOf("target"),
            listOf("target"),
        ),
        LoaderInputObservationDefinition(
            "storedUniqueItemNamespaces",
            "SELECT namespace, count(*) AS cnt FROM game_kv WHERE left(namespace, 3) = 'ut_' GROUP BY namespace ORDER BY namespace ASC",
            listOf("namespace", "cnt"),
            listOf("namespace", "cnt"),
        ),
        LoaderInputObservationDefinition(
            "gameEnv",
            "SELECT key, CAST(value AS VARCHAR) AS value_json FROM game_kv WHERE \"table\" = 'game_env' AND namespace IN ('', 'game_env') ORDER BY id ASC",
            listOf("key", "value_json"),
            listOf("key", "value_json"),
        ),
        LoaderInputObservationDefinition(
            "nationEnv",
            "SELECT namespace, key, CAST(value AS VARCHAR) AS value_json FROM nation_env ORDER BY id ASC",
            listOf("namespace", "key", "value_json"),
            listOf("namespace", "key", "value_json"),
        ),
        LoaderInputObservationDefinition(
            "inheritancePoints",
            "SELECT namespace, key, CAST(value AS VARCHAR) AS value_json FROM game_kv WHERE \"table\" = 'inheritance' AND namespace LIKE 'inheritance_%' ORDER BY id ASC",
            listOf("namespace", "key", "value_json"),
            listOf("namespace", "key", "value_json"),
        ),
        LoaderInputObservationDefinition(
            "generalRankValues",
            "SELECT general_id, type, value FROM rank_data ORDER BY general_id, id",
            listOf("general_id", "type", "value"),
            listOf("general_id", "type", "value"),
        ),
        LoaderInputObservationDefinition(
            "nations",
            "SELECT id, name, color, capital_city_id, gold, rice, tech, power, level, type_code, meta FROM nation WHERE world_id = ? ORDER BY id ASC",
            listOf("id", "name", "color", "capital_city_id", "gold", "rice", "tech", "power", "level", "type_code", "meta"),
            listOf("id", "name", "color", "capital_city_id", "gold", "rice", "tech", "power", "level", "type_code", "meta"),
            inventorySource = NATION_INVENTORY_SOURCE,
        ),
        LoaderInputObservationDefinition(
            "cities",
            """
            SELECT id, name, nation_id, level, state, supply_state, front_state,
                   pop, pop_max, dead, agri, agri_max, comm, comm_max, secu, secu_max, trust,
                   def, def_max, wall, wall_max, trade, region, term, officer_set, conflict, meta
              FROM city
             WHERE world_id = ?
             ORDER BY id ASC
            """.trimIndent(),
            listOf("id", "name", "nation_id", "level", "state", "supply_state", "front_state", "pop", "pop_max", "dead", "agri", "agri_max", "comm", "comm_max", "secu", "secu_max", "trust", "def", "def_max", "wall", "wall_max", "trade", "region", "term", "officer_set", "conflict", "meta"),
            listOf("id", "name", "nation_id", "level", "state", "supply_state", "front_state", "pop", "pop_max", "dead", "agri", "agri_max", "comm", "comm_max", "secu", "secu_max", "trust", "def", "def_max", "wall", "wall_max", "trade", "region", "term", "officer_set", "conflict", "meta"),
            inventorySource = CITY_INVENTORY_SOURCE,
        ),
        LoaderInputObservationDefinition(
            "generals",
            """
            SELECT id, name, nation_id, city_id, troop_id, npc_state, affinity,
                   leadership, strength, intel, politics, charm, experience, dedication, officer_level,
                   injury, gold, rice, crew, crew_type_id, train, atmos, age,
                   weapon_code, book_code, horse_code, item_code,
                   turn_time, recent_war_time, user_id, born_year, dead_year, picture, image_server,
                   start_age, personal_code, special_code, special2_code, officer_city,
                   last_turn, penalty, meta
              FROM general
             WHERE world_id = ?
             ORDER BY id ASC
            """.trimIndent(),
            listOf("id", "name", "nation_id", "city_id", "troop_id", "npc_state", "affinity", "leadership", "strength", "intel", "politics", "charm", "experience", "dedication", "officer_level", "injury", "gold", "rice", "crew", "crew_type_id", "train", "atmos", "age", "weapon_code", "book_code", "horse_code", "item_code", "turn_time", "recent_war_time", "user_id", "born_year", "dead_year", "picture", "image_server", "start_age", "personal_code", "special_code", "special2_code", "officer_city", "last_turn", "penalty", "meta"),
            listOf("id", "name", "nation_id", "city_id", "troop_id", "npc_state", "affinity", "leadership", "strength", "intel", "politics", "charm", "experience", "dedication", "officer_level", "injury", "gold", "rice", "crew", "crew_type_id", "train", "atmos", "age", "weapon_code", "book_code", "horse_code", "item_code", "turn_time", "recent_war_time", "user_id", "born_year", "dead_year", "picture", "image_server", "start_age", "personal_code", "special_code", "special2_code", "officer_city", "last_turn", "penalty", "meta"),
            inventorySource = GENERAL_INVENTORY_SOURCE,
        ),
        LoaderInputObservationDefinition(
            "diplomacy",
            "SELECT src_nation_id, dest_nation_id, state_code, term, is_dead, meta FROM diplomacy ORDER BY id ASC",
            listOf("src_nation_id", "dest_nation_id", "state_code", "term", "is_dead", "meta"),
            listOf("src_nation_id", "dest_nation_id", "state_code", "term", "is_dead", "meta"),
        ),
        LoaderInputObservationDefinition(
            "generalAccessLogs",
            "SELECT general_id, user_id, last_refresh, refresh, refresh_total, refresh_score, refresh_score_total FROM general_access_log ORDER BY general_id ASC",
            listOf("general_id", "user_id", "last_refresh", "refresh", "refresh_total", "refresh_score", "refresh_score_total"),
            listOf("general_id", "user_id", "last_refresh", "refresh", "refresh_total", "refresh_score", "refresh_score_total"),
        ),
    )
    private val definitionsById = definitions.associateBy(LoaderInputObservationDefinition::id)

    fun requireInventoryBinding() {
        val inventory = LoaderInputInventory.current
        require(definitions.map(LoaderInputObservationDefinition::id) == inventory.ids) {
            "Baseline loader-input observation definitions diverged from the checked-in inventory"
        }
        for (definition in definitions) {
            val inventoryInput = inventory.byId.getValue(definition.id)
            require(definition.loaderColumns == inventoryInput.loaderColumns) {
                "Baseline loader-input loader columns diverged from the checked-in inventory for ${definition.id}"
            }
            require(definition.payloadColumns == inventoryInput.payloadColumns) {
                "Baseline loader-input payload columns diverged from the checked-in inventory for ${definition.id}"
            }
            definition.inventorySource?.let { source ->
                definition.countQuery?.let { countQuery ->
                    require(source == ngGamesInventorySource(countQuery, definition.sourceQuery)) {
                        "Baseline loader-input executable source diverged from its count/active queries for ${definition.id}"
                    }
                }
                require(source == inventoryInput.source) {
                    "Baseline loader-input executable source diverged from the checked-in inventory for ${definition.id}"
                }
            }
            definition.inventoryRetainedAs?.let { retainedAs ->
                require(retainedAs == inventoryInput.retainedAs) {
                    "Baseline loader-input retained semantics diverged from the checked-in inventory for ${definition.id}"
                }
            }
        }
    }

    fun aggregate(jdbc: JdbcTemplate, inputId: String, vararg arguments: Any?): SourceAggregate {
        requireInventoryBinding()
        require(inputId != "ngGames") {
            "ngGames must use the count-plus-active-server loader-input observer"
        }
        val definition = definitionsById.getValue(inputId)
        val payloadExpression = payloadExpression(definition.payloadColumns)
        val query = "SELECT count(*) AS source_rows, COALESCE(sum($payloadExpression), 0) AS payload_bytes FROM (${definition.sourceQuery}) AS source"
        return jdbc.query(
            query,
            { rs, _ -> SourceAggregate(rs.getLong("source_rows"), rs.getLong("payload_bytes")) },
            *arguments,
        ).single()
    }

    fun aggregateNgGames(jdbc: JdbcTemplate, activeServerId: String?): ObservedLoaderInputMetric {
        requireInventoryBinding()
        val definition = definitionsById.getValue("ngGames")
        val countQuery = checkNotNull(definition.countQuery) {
            "ngGames loader-input observer is missing its full-cardinality count query"
        }
        val fullCardinality = jdbc.query(
            countQuery,
            { rs, _ -> SourceAggregate(rs.getLong("source_rows"), rs.getLong("payload_bytes")) },
        ).single()
        if (activeServerId == null) return composeNgGamesObservation(fullCardinality, null)

        require(definition.payloadColumns.firstOrNull() == NG_GAMES_SERVER_COUNT_COLUMN) {
            "ngGames loader-input observer must retain the full server-count scalar first"
        }
        val activePayloadColumns = definition.payloadColumns.drop(1)
        require(activePayloadColumns.isNotEmpty()) {
            "ngGames loader-input observer must retain active-game fields"
        }
        val activePayloadExpression = payloadExpression(activePayloadColumns)
        val activeGame = jdbc.query(
            "SELECT count(*) AS source_rows, COALESCE(sum($activePayloadExpression), 0) AS payload_bytes FROM (${definition.sourceQuery}) AS source",
            { rs, _ -> SourceAggregate(rs.getLong("source_rows"), rs.getLong("payload_bytes")) },
            activeServerId,
        ).single()
        require(activeGame.sourceRows == 1L) {
            "ngGames active-server observation expected exactly one row for server_id=$activeServerId"
        }
        return composeNgGamesObservation(
            fullCardinality = fullCardinality,
            activeGame = activeGame,
        )
    }

    fun composeNgGamesObservation(
        fullCardinality: SourceAggregate,
        activeGame: SourceAggregate?,
    ): ObservedLoaderInputMetric {
        requireInventoryBinding()
        require(fullCardinality.sourceRows >= 0L && fullCardinality.payloadBytes >= 0L) {
            "ngGames full-cardinality observation must be non-negative"
        }
        activeGame?.let { selected ->
            require(selected.sourceRows == 1L && selected.payloadBytes >= 0L) {
                "ngGames active-server observation must contain exactly one non-negative payload row"
            }
            require(fullCardinality.sourceRows >= selected.sourceRows) {
                "ngGames active-server observation cannot exceed full ng_games cardinality"
            }
        }
        return ObservedLoaderInputMetric(
            sourceRows = fullCardinality.sourceRows,
            retainedItems = 1L + if (activeGame == null) 0L else 1L,
            payloadBytes = Math.addExact(fullCardinality.payloadBytes, activeGame?.payloadBytes ?: 0L),
        )
    }

    private fun payloadExpression(columns: List<String>): String = columns.joinToString(" + ") { column ->
        "COALESCE(octet_length((source.\"$column\")::text), 0)"
    }

    private fun ngGamesInventorySource(countQuery: String, activeQuery: String): String =
        "countQuery=$countQuery | activeQuery=$activeQuery"

    fun contract(): Map<String, Any> {
        requireInventoryBinding()
        return LoaderInputInventory.current.observationContract
    }
}

private object LoaderInputInventory {
    data class Input(
        val id: String,
        val source: String,
        val retainedAs: String,
        val loaderColumns: List<String>,
        val payloadColumns: List<String>,
    )

    data class Value(
        val inputs: List<Input>,
        val payloadByteSemantics: String,
        val sha256: String,
    ) {
        val ids: List<String> = inputs.map(Input::id)
        val byId: Map<String, Input> = inputs.associateBy(Input::id)
        val observationContract: Map<String, Any> = linkedMapOf(
            "payloadByteSemantics" to payloadByteSemantics,
            "inputs" to linkedMapOf<String, Any>().apply {
                for (input in inputs) {
                    this[input.id] = linkedMapOf(
                        "loaderColumns" to input.loaderColumns,
                        "payloadColumns" to input.payloadColumns,
                    )
                }
            },
        )
    }

    val current: Value by lazy {
        val bytes = checkNotNull(
            CqrsBaselineMain::class.java.getResourceAsStream(
                "/opensamguk/engine/baseline/loader-input-inventory.json",
            ),
        ) { "Baseline loader-input inventory resource is missing" }.use { it.readBytes() }
        val root = ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readTree(bytes)
            ?: error("Baseline loader-input inventory resource is empty")
        require(root.isObject && root.fieldNames().asSequence().toSet() == setOf("schemaVersion", "payloadByteSemantics", "inputs")) {
            "Baseline loader-input inventory has an unexpected field set"
        }
        require(root.get("schemaVersion")?.textValue() == LOADER_INPUT_INVENTORY_SCHEMA_VERSION) {
            "Baseline loader-input inventory schema is unsupported"
        }
        val payloadByteSemantics = root.get("payloadByteSemantics")?.textValue()
        require(payloadByteSemantics == PAYLOAD_BYTE_SEMANTICS) {
            "Baseline loader-input inventory payload byte semantics are unsupported"
        }
        val inputs = root.get("inputs")
        require(inputs != null && inputs.isArray) { "Baseline loader-input inventory inputs must be an array" }
        val parsedInputs = inputs.mapIndexed { index, input ->
            require(input.isObject && input.fieldNames().asSequence().toSet() == setOf("id", "source", "retainedAs", "loaderColumns", "payloadColumns")) {
                "Baseline loader-input inventory input $index has an unexpected field set"
            }
            val id = input.get("id")?.textValue()
            require(id != null && id.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) {
                "Baseline loader-input inventory input $index id is invalid"
            }
            val source = input.get("source")?.textValue()
            require(!source.isNullOrBlank()) {
                "Baseline loader-input inventory input $index source is invalid"
            }
            val retainedAs = input.get("retainedAs")?.textValue()
            require(!retainedAs.isNullOrBlank()) {
                "Baseline loader-input inventory input $index retainedAs is invalid"
            }
            val loaderColumns = requiredColumns(input, "loaderColumns", index)
            val payloadColumns = requiredColumns(input, "payloadColumns", index)
            require(loaderColumns.containsAll(payloadColumns)) {
                "Baseline loader-input inventory input $index payload columns are not loader columns"
            }
            Input(id, source, retainedAs, loaderColumns, payloadColumns)
        }
        require(parsedInputs.isNotEmpty() && parsedInputs.map(Input::id).distinct().size == parsedInputs.size) {
            "Baseline loader-input inventory ids must be non-empty and unique"
        }
        Value(
            inputs = parsedInputs,
            payloadByteSemantics = payloadByteSemantics,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) },
        )
    }

    private fun requiredColumns(input: JsonNode, field: String, index: Int): List<String> {
        val values = input.get(field)
        require(values != null && values.isArray && values.size() > 0) {
            "Baseline loader-input inventory input $index $field must be a non-empty array"
        }
        val columns = values.map { value ->
            val column = value.textValue()
            require(column != null && column.matches(Regex("[a-z][a-z0-9_]*"))) {
                "Baseline loader-input inventory input $index $field has an invalid column"
            }
            column
        }
        require(columns.distinct().size == columns.size) {
            "Baseline loader-input inventory input $index $field repeats a column"
        }
        return columns
    }
}

private data class FixtureDescriptor(
    val profile: BaselineProfile,
    val baseRows: Int,
    val coldHistoryRows: Int,
    val fixedHotLogRows: Int,
    val productionShapeFixture: ProductionShapeFixtureConfig?,
    val fixtureSha256: String,
) {
    val isLocalSanitizedAggregateSurrogate: Boolean
        get() = productionShapeFixture?.isLocalSanitizedAggregateSurrogate == true

    fun asMap(): Map<String, Any> {
        val fixture = productionShapeFixture
        if (fixture?.isLocalSanitizedAggregateSurrogate == true) {
            return linkedMapOf(
                "kind" to LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND,
                "label" to "checked-in deterministic local sanitized aggregate surrogate; no production, live, or seeded-db observation",
                "policyId" to requireNotNull(fixture.policyId),
                "policySha256" to requireNotNull(fixture.policySha256),
                "loaderInputInventorySha256" to fixture.loaderInputInventorySha256,
                "payloadByteSemantics" to fixture.payloadByteSemantics,
                "loaderInputObservation" to LoaderInputObservation.contract(),
                "profile" to profile.wireName,
                "baseRows" to baseRows,
                "coldHistoryMultiplier" to profile.coldHistoryMultiplier,
                "coldHistoryRows" to coldHistoryRows,
                "fixedHotLogRows" to fixedHotLogRows,
                "payloadSizeBytes" to fixture.payloadSizeBytes,
                "sha256" to fixtureSha256,
            )
        }
        if (fixture != null) {
            return linkedMapOf(
                "kind" to SANITIZED_PRODUCTION_FIXTURE_KIND,
                "label" to "sanitized production-shape aggregate fixture; not production data",
                "manifestSha256" to requireNotNull(fixture.manifestSha256),
                "loaderInputInventorySha256" to fixture.loaderInputInventorySha256,
                "payloadByteSemantics" to fixture.payloadByteSemantics,
                "loaderInputObservation" to LoaderInputObservation.contract(),
                "profile" to profile.wireName,
                "baseRows" to baseRows,
                "coldHistoryMultiplier" to profile.coldHistoryMultiplier,
                "coldHistoryRows" to coldHistoryRows,
                "fixedHotLogRows" to fixedHotLogRows,
                "payloadSizeBytes" to fixture.payloadSizeBytes,
                "sha256" to fixtureSha256,
            )
        }
        return linkedMapOf(
        "kind" to "synthetic-scenario-seed-proxy",
        "label" to "synthetic production-shaped seed proxy; not production data or a sanitized production shape",
        "scenario" to SCENARIO_CODE,
        "profile" to profile.wireName,
        "baseRows" to baseRows,
        "coldHistoryMultiplier" to profile.coldHistoryMultiplier,
        "coldHistoryRows" to coldHistoryRows,
        "fixedHotLogRows" to fixedHotLogRows,
        "logPayloadCharacters" to LOG_PAYLOAD_CHARACTERS,
        "payloadByteSemantics" to PAYLOAD_BYTE_SEMANTICS,
        "loaderInputObservation" to LoaderInputObservation.contract(),
        "sha256" to fixtureSha256,
    )
    }

    companion object {
        fun local(
            profile: BaselineProfile,
            baseRows: Int,
            fixture: ProductionShapeFixtureConfig,
        ): FixtureDescriptor {
            val materialization = listOf(
                "schema=$FIXTURE_SCHEMA_VERSION",
                "fixture=$LOCAL_SANITIZED_AGGREGATE_FIXTURE_KIND",
                "policyId=${requireNotNull(fixture.policyId)}",
                "policySha256=${requireNotNull(fixture.policySha256)}",
                "profile=${profile.wireName}",
                "baseRows=$baseRows",
                "coldHistoryRows=${fixture.coldHistoryRows}",
                "fixedHotLogRows=${fixture.fixedHotActionRows}",
                "hotActionPayloadBytes=${fixture.payloadSizeBytes.getValue("hotAction")}",
                "coldHistoryPayloadBytes=${fixture.payloadSizeBytes.getValue("coldHistory")}",
            ).joinToString("\n")
            val fixtureSha256 = MessageDigest.getInstance("SHA-256")
                .digest(materialization.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return FixtureDescriptor(
                profile = profile,
                baseRows = baseRows,
                coldHistoryRows = fixture.coldHistoryRows,
                fixedHotLogRows = fixture.fixedHotActionRows,
                productionShapeFixture = fixture,
                fixtureSha256 = fixtureSha256,
            )
        }
    }
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
