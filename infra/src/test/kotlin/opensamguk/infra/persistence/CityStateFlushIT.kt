package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * W0-8 city.state(V14) flush 채널의 실DB 증명 (P0-36).
 *
 * PHP 정본: `city.state INT(2)`(hwe/sql/schema.sql:187) — RaiseDisaster.php가 매월
 * `state<=10 → 0` 무조건 리셋 후 선택 도시에 1~9 이벤트 코드를 쓴다. 지금까지 이 값은
 * 엔진 메모리 전용이라 재기동 시 유실됐다(P0-36 근본 원인의 절반).
 *
 * 이 IT는 Flyway 전체 마이그레이션(V14 포함) 위에서 [JdbcFlushExecutor.cityUpdate]가 방출하는
 * UPDATE가 실제 Postgres `city.state` 컬럼과 정합함 + [CityRowMapper]가 그 행을 손실 없이
 * 되읽음을 증명한다 (repo 교훈: flush 변경 = real-Postgres IT 필수).
 *
 * Docker 미가용 시 Testcontainers가 컨테이너를 못 띄우므로 IT는 skip — fail이 아니다 (CLAUDE.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CityStateFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 181, "current_month" to 1)

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 181, 1, 3600)",
            MapSqlParameterSource(),
        )
        // V14 DEFAULT 0으로 시드되는 도시 한 행 (state 명시 생략 — 마이그레이션 기본값 검증 겸용).
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (1, 5, '성도', 5, 2, 1, 0, 50000, 100000,
                 1000, 2000, 800, 2000, 500, 1000, 50, 100, 1000, 2000,
                 1000, 2000, 1, CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun city(state: Int, dead: Int = 0) = City(
        id = 5, nationId = 2, level = 5,
        commerce = 800, commerceMax = 2000,
        agriculture = 1000, agricultureMax = 2000,
        supplyState = 1, frontState = 0, trust = 50.0,
        security = 500, securityMax = 1000,
        defense = 1000, defenseMax = 2000,
        wall = 1000, wallMax = 2000,
        population = 50000, populationMax = 100000,
        dead = dead,
        trade = 100, region = 1,
        state = state,
    )

    @Test
    fun `재해 stateCode가 cityUpdate로 영속되고 RowMapper로 손실 없이 되읽힌다`() {
        // V14 기본값: 시드 직후 state = 0.
        assertEquals(0, selectState())

        // RaiseDisaster가 stateCode 7(혹한 등)을 기록한 도시를 flush.
        executor.flush(FlushPayload(worldId = opensamguk.common.world.WorldId(1), worldStateUpdate = ws(), updatedCities = listOf(city(state = 7, dead = 321))))
        assertEquals(7, selectState())
        assertEquals(321, selectDead())

        // 되읽기: CityRowMapper.fromRow가 state를 그대로 실어 round-trip이 무손실이다.
        val row = jdbc.jdbcTemplate.queryForMap("SELECT * FROM city WHERE id = 5")
        assertEquals(7, CityRowMapper.fromRow(row).state)
        assertEquals(321, CityRowMapper.fromRow(row).dead)

        // 다음 달 무조건 리셋(state<=10 → 0)도 같은 경로로 영속된다.
        executor.flush(FlushPayload(worldId = opensamguk.common.world.WorldId(1), worldStateUpdate = ws(), updatedCities = listOf(city(state = 0))))
        assertEquals(0, selectState())
    }

    private fun selectState(): Int =
        jdbc.jdbcTemplate.queryForObject("SELECT state FROM city WHERE id = 5", Int::class.java)!!

    private fun selectDead(): Int =
        jdbc.jdbcTemplate.queryForObject("SELECT dead FROM city WHERE id = 5", Int::class.java)!!
}
