package opensamguk.engine.boot

import opensamguk.infra.seed.ScenarioImporter
import opensamguk.infra.seed.Scenario
import opensamguk.infra.seed.ScenarioJson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * F1a — boots a FRESH/empty PostgreSQL into a playable `scenario_1010` world by seeding rows.
 *
 * Mirrors `app/gateway-api/.../config/AdminSeeder.kt` idempotency: if the world singleton already
 * exists (`SELECT count(*) FROM world_state > 0`) it logs and skips; otherwise it loads the two
 * committed `infra` resources and runs [ScenarioImporter.importAll], then logs the final row counts.
 *
 * This is an [ApplicationRunner] so it runs once at daemon start. The seed is ALSO invoked
 * defensively (and idempotently) by [WorldSnapshotLoader] right before it builds the snapshot, so the
 * seed→load ordering holds regardless of bean lifecycle — see [SeedBootstrap.ensureSeeded].
 *
 * **JDBC-only — NOT a one-daemon-write-rule violation.** Uses [JdbcTemplate] only (same category as
 * Flyway / AdminSeeder), never a JPA `EntityManager` or `ChangeRecorder`. It lives in the
 * `opensamguk.engine.boot` package, OUTSIDE the architecture-test write-path scan
 * (`opensamguk.engine.{flush,turn,run}`).
 *
 * Optional env fences:
 *  - `SCENARIO_SEED_ENABLED` (default true) — set false to disable seeding entirely.
 *  - `SCENARIO_CODE` (default `scenario_1010`) — selects the committed resource set.
 */
@Component
class ScenarioSeedRunner(
    private val jdbc: JdbcTemplate,
    private val bootstrap: SeedBootstrap,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(ScenarioSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        bootstrap.ensureSeeded(jdbc)
    }
}

/**
 * The shared, idempotent seed body. Both [ScenarioSeedRunner] (at boot) and [WorldSnapshotLoader]
 * (before snapshot build) call [ensureSeeded]; the `world_state` emptiness gate makes a second call a
 * no-op, so the seed→load order is guaranteed without depending on bean init ordering.
 */
@Component
class SeedBootstrap(
    @Value("\${SCENARIO_CODE:scenario_1010}") private val scenarioCode: String,
    @Value("\${SCENARIO_SEED_ENABLED:true}") private val seedEnabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(SeedBootstrap::class.java)

    /** Seed the world iff `world_state` is empty. Returns true if it seeded, false if it skipped. */
    @Synchronized
    fun ensureSeeded(jdbc: JdbcTemplate): Boolean {
        if (!seedEnabled) {
            log.info("Scenario seed skipped — SCENARIO_SEED_ENABLED=false")
            return false
        }

        val worldCount = jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java) ?: 0
        if (worldCount > 0) {
            log.info("World already exists (world_state={}) — scenario seed skipped (idempotent)", worldCount)
            return false
        }

        val scenario = ScenarioJson.loadScenario(readResource("scenario/$scenarioCode.json"))
        val mapName = scenarioMapName(scenario)
        val cities = ScenarioJson.loadMapCities(readResource("map/$mapName.json"))

        log.info(
            "Seeding fresh world '{}' — map={} nations={} generals={} cities={}",
            scenarioCode, mapName, scenario.nations.size, scenario.generals.size, cities.size,
        )
        val importer = ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = scenarioCode)
        val counts = importer.importAll(jdbc)
        log.info(
            "Scenario seed complete — world_state={} nation={} city={} general={} general_turn={} " +
                "nation_turn={} diplomacy={} rank_data={} ng_games={}",
            counts.worldState, counts.nation, counts.city, counts.general, counts.generalTurn,
            counts.nationTurn, counts.diplomacy, counts.rankData, counts.ngGames,
        )
        return true
    }

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found on classpath: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun scenarioMapName(scenario: Scenario): String {
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(scenario.map)
        merged.putAll(scenario.const)
        return merged["mapName"] as? String ?: "che"
    }
}
