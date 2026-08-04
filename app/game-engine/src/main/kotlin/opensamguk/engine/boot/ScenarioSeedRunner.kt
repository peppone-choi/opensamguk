package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.seed.EffectiveScenarioResolver
import opensamguk.infra.seed.Scenario
import opensamguk.infra.seed.ScenarioImporter
import opensamguk.infra.seed.ScenarioJson
import opensamguk.infra.seed.ScenarioSeedCoordinator
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * F1a — boots the configured [WorldId] into a playable `scenario_1010` world when seed admission
 * permits it.
 *
 * [ScenarioSeedCoordinator] admits an import only when `world_state` is empty. It skips only when
 * that table contains exactly the configured world id, and rejects every other identity set. On an
 * admitted import it loads the two committed `infra` resources and runs [ScenarioImporter.importAll],
 * then logs the final row counts.
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
 *  - `SCENARIO_SEED_ENABLED` (default true) — set false to disable fresh-world seeding.
 *  - `SCENARIO_CODE` (default `scenario_1010`) — selects the committed resource set.
 *  - `SCENARIO_DIR` — optional external directory containing `${SCENARIO_CODE}.json`.
 *  - `SCENARIO_QA_TURNTERM` — QA-only opt-in; only `1` reduces a fresh seed to one-minute cadence.
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
 * (before snapshot build) call [ensureSeeded]. [ScenarioSeedCoordinator] makes a second call a no-op
 * only when `world_state` contains exactly the configured world id, so the seed→load order is
 * guaranteed without depending on bean init ordering.
 */
class SeedBootstrap(
    private val scenarioCode: String = "scenario_1010",
    private val seedEnabled: Boolean = true,
    private val scenarioDir: String = "",
    private val qaTurnTerm: String? = null,
    private val worldId: WorldId,
) {
    private val log = LoggerFactory.getLogger(SeedBootstrap::class.java)
    private val scenarioResolver = EffectiveScenarioResolver(scenarioDir)
    private val turnTerm: Int = when (qaTurnTerm) {
        null, "" -> DEFAULT_TURN_TERM
        "1" -> 1
        else -> throw IllegalArgumentException("SCENARIO_QA_TURNTERM must be exactly 1 when set: $qaTurnTerm")
    }

    /**
     * Seed through configured-world admission. Returns true when it imports, false only when exactly
     * the configured `world_state.id` already exists; all other identity sets are rejected.
     */
    fun ensureSeeded(jdbc: JdbcTemplate): Boolean {
        if (!seedEnabled) {
            log.info("Fresh-world scenario seed skipped — SCENARIO_SEED_ENABLED=false")
            return false
        }

        val admission = ScenarioSeedCoordinator(jdbc).ensureSeeded(worldId) {
            val scenarioNumber = scenarioNumber()
            val scenario = loadScenario()
            val mapName = scenarioMapName(scenario)
            val cities = ScenarioJson.loadMapCities(readResource("map/$mapName.json"))
            log.info(
                "Seeding fresh world '{}' — map={} nations={} generals={} cities={}",
                scenarioCode, mapName, scenario.nations.size, scenario.generals.size, cities.size,
            )
            ScenarioImporter(
                scenario = scenario,
                cities = cities,
                scenarioCode = scenarioCode,
                scenarioNumber = scenarioNumber,
                turnTerm = turnTerm,
            )
        }
        if (!admission.seeded) {
            log.info("World already exists as configured world_state.id={} — scenario seed skipped", worldId.value)
            return false
        }

        val counts = requireNotNull(admission.counts)
        log.info(
            "Scenario seed complete — world_state={} nation={} city={} general={} general_turn={} " +
                "nation_turn={} diplomacy={} rank_data={} ng_games={} event={}",
            counts.worldState, counts.nation, counts.city, counts.general, counts.generalTurn,
            counts.nationTurn, counts.diplomacy, counts.rankData, counts.ngGames, counts.event,
        )
        return true
    }

    internal fun scenarioNumber(): Int {
        val numericSuffix = SCENARIO_CODE_PATTERN.matchEntire(scenarioCode)
            ?.groupValues
            ?.get(1)
            ?: throw IllegalArgumentException("SCENARIO_CODE must be canonical scenario_<number>: $scenarioCode")
        return numericSuffix.toIntOrNull()
            ?: throw IllegalArgumentException("SCENARIO_CODE is outside the Int range: $scenarioCode")
    }

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found on classpath: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    internal fun readScenarioJson(): String = scenarioResolver.readScenarioJson(scenarioCode)

    internal fun loadScenario(): Scenario = scenarioResolver.resolve(scenarioCode)

    private fun scenarioMapName(scenario: Scenario): String {
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(scenario.map)
        merged.putAll(scenario.const)
        return merged["mapName"] as? String ?: "che"
    }

    private companion object {
        const val DEFAULT_TURN_TERM = 60
        val SCENARIO_CODE_PATTERN = Regex("scenario_(0|[1-9]\\d*)")
    }
}
