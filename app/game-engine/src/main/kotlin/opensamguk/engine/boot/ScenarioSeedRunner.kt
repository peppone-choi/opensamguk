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
 *  - `RESET_TURNTERM` — 어드민/워크플로 리셋이 고른 턴 주기(분). 허용 집합은
 *    `.github/workflows/reset-game-server.yml:157`과 동일하며, 미설정 시 60이다.
 *    `SCENARIO_QA_TURNTERM`이 설정돼 있으면 그쪽이 이긴다.
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
    private val resetTurnTerm: String? = null,
    private val worldId: WorldId,
) {
    private val log = LoggerFactory.getLogger(SeedBootstrap::class.java)
    private val scenarioResolver = EffectiveScenarioResolver(scenarioDir)
    private val turnTerm: Int = resolveTurnTerm(qaTurnTerm, resetTurnTerm)

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
                // turnTerm을 함께 남긴다. 리셋 옵션이 엔진까지 도달했는지 확인할 유일한 관측점이며,
                // 값이 항상 60으로 찍히면 RESET_TURNTERM 전달 경로가 끊긴 것이다(문서 3-A 참조).
                "Seeding fresh world '{}' — map={} nations={} generals={} cities={} turnTerm={}",
                scenarioCode, mapName, scenario.nations.size, scenario.generals.size, cities.size, turnTerm,
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

    internal companion object {
        const val DEFAULT_TURN_TERM = 60
        val SCENARIO_CODE_PATTERN = Regex("scenario_(0|[1-9]\\d*)")

        /**
         * 어드민 리셋이 고를 수 있는 턴 주기(분). `.github/workflows/reset-game-server.yml:157`의
         * `case "$TURN_TERM" in 120|60|30|20|10|5|2|1)`과 **정확히 같은 집합**이어야 한다.
         * 워크플로가 거부하는 값을 백엔드가 받아들이면 두 리셋 경로(워크플로 / 어드민 UI)가
         * 서로 다른 월드를 만든다.
         */
        val ALLOWED_TURN_TERMS = listOf(120, 60, 30, 20, 10, 5, 2, 1)

        private val ASCII_DIGITS = Regex("^[0-9]+$")

        /**
         * 시드 턴 주기를 정한다.
         *
         * `SCENARIO_QA_TURNTERM`은 QA 전용의 좁은 opt-in이라 값이 있으면 이긴다.
         * 그 다음이 리셋 요청에서 온 `RESET_TURNTERM`이고, 둘 다 없으면 [DEFAULT_TURN_TERM]이다.
         *
         * 잘못된 값은 조용히 기본값으로 떨어지지 않고 **부팅을 실패**시킨다. 리셋 옵션이
         * 조용히 무시되어 운영자가 고른 것과 다른 월드가 시드되는 것이 지금 닫으려는 결함이므로,
         * 같은 실패 양상을 다시 만들지 않는다.
         */
        internal fun resolveTurnTerm(qaTurnTerm: String?, resetTurnTerm: String?): Int {
            // QA fence는 **정확히** "1"만 받는다 — 공백조차 허용하지 않는다.
            // `ScenarioMapSeedIT.QA turnterm rejects every value other than one`이 " 1"/"1 "
            // 거부를 이미 고정하고 있는 기존 계약이므로 trim을 넣어 느슨하게 만들지 않는다.
            // 아래 RESET_TURNTERM이 trim을 하는 것과 의도적으로 다르다: 그쪽은 env 파일에서
            // 오는 운영값이라 주변 공백을 관용하고, 이쪽은 좁은 opt-in이라 오타를 그대로 튕긴다.
            when (qaTurnTerm) {
                null, "" -> Unit
                "1" -> return 1
                else -> throw IllegalArgumentException(
                    "SCENARIO_QA_TURNTERM must be exactly 1 when set: $qaTurnTerm",
                )
            }
            val reset = resetTurnTerm?.trim()
            if (reset.isNullOrEmpty()) return DEFAULT_TURN_TERM
            // ASCII 숫자만 받는다. `toIntOrNull`은 Character.digit 기반이라 아라비아-인도 숫자
            // 같은 비-ASCII 자릿수도 파싱하는데(예: "١٢٠" -> 120), 워크플로의 `case` 문
            // (reset-game-server.yml:157)은 리터럴 ASCII만 매치한다. 좁은 쪽에 맞춘다.
            val parsed = if (ASCII_DIGITS.matches(reset)) reset.toIntOrNull() else null
            if (parsed == null || parsed !in ALLOWED_TURN_TERMS) {
                throw IllegalArgumentException(
                    "RESET_TURNTERM must be one of ${ALLOWED_TURN_TERMS.joinToString(",")}: $reset",
                )
            }
            return parsed
        }
    }
}
