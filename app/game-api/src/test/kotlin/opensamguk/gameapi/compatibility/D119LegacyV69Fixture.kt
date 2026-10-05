package opensamguk.gameapi.compatibility

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import opensamguk.gameapi.GameApiApplication

/** C8 public d501 runtime -> isolated V69. No operating dump, image or connection. */
internal class D119LegacyV69Fixture : AutoCloseable {
    private class LegacyPostgres : PostgreSQLContainer<LegacyPostgres>("postgres:16.15-alpine")
    private class LegacyRedis : GenericContainer<LegacyRedis>("redis:7-alpine")
    val mapper = ObjectMapper()
    val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
    private val runId = UUID.randomUUID().toString().replace("-", "")
    val output: Path = root.resolve("app/game-api/build/d119-v69/$runId")
    val manifest: JsonNode = mapper.readTree(root.resolve(
        "app/game-api/src/test/resources/compatibility/d119-v69/source-manifest.json").toFile())
    private val network = Network.newNetwork()
    private val password = UUID.randomUUID().toString()
    private val postgres = LegacyPostgres().apply {
        withDatabaseName("d119_$runId")
        withUsername("d119_fixture")
        withPassword(password)
        withNetwork(network)
        withLabel("opensamguk.d119.run", runId)
    }
    private val redis = LegacyRedis().apply {
        withExposedPorts(6379)
        withNetwork(network)
        withLabel("opensamguk.d119.run", runId)
    }
    private val savedRoot = System.getProperty("opensamguk.artifacts.root")
    private var stage = "NOT_STARTED"
    var application: ConfigurableApplicationContext? = null
        private set
    lateinit var dataSource: DataSource
        private set
    val jdbc get() = JdbcTemplate(dataSource)

    fun prepare() {
        check(System.getenv("GITHUB_ACTIONS") == "true"
            && System.getenv("GITHUB_REPOSITORY") == "peppone-choi/opensamguk")
        stage = "PUBLIC_RUNTIME_PREPARATION"
        val command = mutableListOf("python3", root.resolve("tools/compatibility/d119_v69_fixture.py").toString(),
            "--repo", root.toString(), "--output", output.toString(), "--manifest", root.resolve(
                "app/game-api/src/test/resources/compatibility/d119-v69/source-manifest.json").toString())
        // C8 may supply a real build receipt and its public checkout; both are required.
        System.getProperty("d119.legacy.receipt")?.let { receipt ->
            command += listOf("--receipt", receipt, "--source-root",
                requireNotNull(System.getProperty("d119.legacy.source")))
        }
        run(command, root.resolve("app/game-api/build/d119-$runId-prepare.log"))
        val prepared = mapper.readTree(output.resolve("prepared-runtime.json").toFile())
        check(prepared["sourceSha"].asText() == manifest["oldSourceSha"].asText())
        stage = "ISOLATED_CONTAINERS"
        postgres.start()
        redis.start()
        check(postgres.isRunning && redis.isRunning && postgres.host in setOf("localhost", "127.0.0.1"))
        check(redis.host in setOf("localhost", "127.0.0.1"))
        dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, password)
        stage = "OLD_CLASSPATH_MIGRATION_AND_IMPORT"
        val environment = System.getenv().filterKeys { it in setOf("PATH", "JAVA_HOME", "LANG", "TZ") }.toMutableMap()
        environment["D119_ISOLATED_URL"] = postgres.jdbcUrl
        environment["D119_ISOLATED_USER"] = postgres.username
        environment["D119_ISOLATED_PASSWORD"] = password
        run(prepared["seedCommand"].map { it.asText() }, output.resolve("old-migrate-seed.log"), environment,
            Path.of(prepared["sourceRoot"].asText()))
        check(history() == (1..69).toList()) { "fixture did not retain complete successful V1..69" }
        check(jdbc.queryForObject("SELECT count(*) FROM city WHERE world_id=1", Int::class.java) == 1428)
        check(jdbc.queryForObject("SELECT meta ? 'generation' FROM world_state WHERE id=1", Boolean::class.java) == false)
        writeReceipt("FIXTURE_READY", mapOf("oldEngineWire" to "UNVERIFIED", "newWebWire" to "UNVERIFIED"))
    }

    fun boot(publicKey: String) {
        stage = "CANDIDATE_FULL_APPLICATION_STARTUP"
        System.setProperty("opensamguk.artifacts.root", root.toString())
        val properties = linkedMapOf(
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to password,
            "spring.flyway.enabled" to "false",
            "spring.jpa.hibernate.ddl-auto" to "validate",
            "spring.data.redis.host" to redis.host,
            "spring.data.redis.port" to redis.getMappedPort(6379).toString(),
            "server.port" to "0", "jwt.public-key" to publicKey,
            "SERVER_ID" to "d119fixture", "SERVER_GENERATION" to "1",
            "jwt.legacy-secret" to "", "jwt.legacy-accept-until" to "",
            "opensamguk.world-id" to "1", "opensamguk.profile" to "che:scenario_2",
            "server-admission.server-id" to "d119fixture",
            "server-admission.gateway-origin" to "http://127.0.0.1:1",
            "server-admission.service-token" to UUID.randomUUID().toString(),
            "member-profile.gateway-origin" to "http://127.0.0.1:1",
            "member-profile.service-token" to UUID.randomUUID().toString(),
            "sentry.dsn" to "", "spring.profiles.active" to "",
        )
        // CLI properties bind the owned DB even if the runner has ambient application settings.
        application = SpringApplicationBuilder(GameApiApplication::class.java)
            .run(*properties.map { "--${it.key}=${it.value}" }.toTypedArray())
        stage = "CANDIDATE_READS_AND_FLUSH"
    }

    fun history(): List<Int> = jdbc.queryForList(
        "SELECT version::integer FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY version::integer",
        Int::class.java,
    )

    /** Fingerprint all ordinary public-table rows, columns, indexes, constraints and views. */
    fun fingerprint(): Map<String, String> {
        val result = sortedMapOf<String, String>()
        val schema = jdbc.queryForList("""
            SELECT 'column' AS kind, table_name AS relation, column_name AS name,
                concat_ws('|',data_type,udt_name,is_nullable,column_default) AS definition
            FROM information_schema.columns WHERE table_schema='public'
            UNION ALL SELECT 'index',tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'
            UNION ALL SELECT 'constraint',r.relname,c.conname,pg_get_constraintdef(c.oid)
                FROM pg_constraint c JOIN pg_class r ON c.conrelid=r.oid
                JOIN pg_namespace n ON r.relnamespace=n.oid WHERE n.nspname='public'
            UNION ALL SELECT 'view',viewname,viewname,definition FROM pg_views WHERE schemaname='public'
            ORDER BY kind,relation,name,definition
        """.trimIndent())
        result["schema"] = digest(mapper.writeValueAsBytes(schema))
        val tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String::class.java)
        tables.forEach { table ->
            val quoted = table.replace("\"", "\"\"")
            val rows = jdbc.queryForList("SELECT to_jsonb(t)::text FROM public.\"$quoted\" t ORDER BY to_jsonb(t)::text", String::class.java)
            result[table] = digest(mapper.writeValueAsBytes(rows)) + ":" + rows.size
        }
        return result
    }

    fun writeReceipt(status: String, facts: Map<String, Any?> = emptyMap()) {
        Files.createDirectories(output)
        val receipt = linkedMapOf<String, Any?>("status" to status, "stage" to stage,
            "oldSourceSha" to manifest["oldSourceSha"].asText(),
            "candidateGitHead" to ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile())
                .start().inputStream.bufferedReader().readText().trim(),
            "runId" to runId, "operatingAccess" to false, "generationMetadata" to null,
            "apiGenerationConfigured" to application?.environment?.getProperty("SERVER_GENERATION"),
            "externalScenarioEquivalence" to "UNKNOWN", "oldEngineWire" to "UNVERIFIED", "newWebWire" to "UNVERIFIED")
        if (postgres.isRunning) receipt["postgresContainerId"] = postgres.containerId
        if (redis.isRunning) receipt["redisContainerId"] = redis.containerId
        receipt.putAll(facts)
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("compatibility-receipt.json").toFile(), receipt)
        // Existing normal CI preserves JUnit system-out in its XML artifact.
        println("D119_COMPATIBILITY_RECEIPT " + mapper.writeValueAsString(receipt))
    }

    fun failure(error: Throwable) = writeReceipt("FAILED", mapOf(
        "exceptionType" to error.javaClass.name,
        "message" to error.message.orEmpty().replace(password, "[fixture password]").take(2000),
    ))

    private fun run(command: List<String>, log: Path, environment: Map<String, String>? = null, cwd: Path = root) {
        Files.createDirectories(log.parent)
        val builder = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        if (environment != null) { builder.environment().clear(); builder.environment().putAll(environment) }
        val process = builder.start()
        if (!process.waitFor(2100, TimeUnit.SECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
            error("owned fixture process timed out; original log retained at $log")
        }
        // Store owned fixture credentials nowhere in saved logs.
        if (Files.exists(log)) Files.writeString(log, Files.readString(log).replace(password, "[fixture password]"))
        check(process.exitValue() == 0) {
            "fixture process exit=${process.exitValue()}; original log at $log\n" +
                Files.readString(log).takeLast(8000)
        }
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        listOf<() -> Unit>({ application?.close() }, { redis.stop() }, { postgres.stop() }, { network.close() })
            .forEach { action -> runCatching(action).exceptionOrNull()?.let(failures::add) }
        if (savedRoot == null) System.clearProperty("opensamguk.artifacts.root")
        else System.setProperty("opensamguk.artifacts.root", savedRoot)
        if (failures.isNotEmpty()) {
            writeReceipt("CLEANUP_FAILED", mapOf("cleanupExceptions" to failures.map { it.javaClass.name }))
            throw failures.first()
        }
        println("D119_OWNED_CLEANUP_COMPLETE $runId")
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
