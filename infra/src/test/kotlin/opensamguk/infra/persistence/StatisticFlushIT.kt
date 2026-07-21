package opensamguk.infra.persistence

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
 * 연경계(새 달 == 1월) checkStatistic 채널의 실DB 증명 — 2026-06-09 prod 턴동결 회귀.
 *
 * 동결 2층 구조였다:
 *  1. aux 직렬화 크래시 (kotlinx `Json.encodeToString(Map<String,Any?>)` 런타임 실패)
 *  2. `statistic` 테이블 DDL 부재 — 직렬화를 고쳐도 INSERT가
 *     `relation "statistic" does not exist`로 throw → 연경계 tick 영구 실패.
 *
 * 이 IT는 Flyway 전체 마이그레이션 위에서 [JdbcFlushExecutor.statisticInsertMany]가 방출하는
 * SQL이 실제 Postgres `statistic` 테이블(V13)과 정합함을 증명한다 (repo 교훈: flush 변경 =
 * 유닛 green만으론 부족, real DB로 닫고 푸시).
 *
 * Docker 미가용 시 Testcontainers가 컨테이너를 못 띄우므로 IT는 skip — fail이 아니다 (CLAUDE.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatisticFlushIT {

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
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `연경계 statistic INSERT가 실제 테이블에 영속화된다 - 이종 중첩 aux 포함`() {
        // DaemonLoopConfig의 CheckStatistic 훅이 만드는 것과 동일한 형태의 컬럼 맵.
        // aux는 반드시 MetaJson(PHP Json::encode byte-faithful) 경로로 인코딩된 문자열이다.
        val aux = MetaJson.encode(
            linkedMapOf<String, Any?>(
                "generals" to linkedMapOf<String, Any?>(
                    "avg" to linkedMapOf<String, Any?>("avggold" to 1000, "avgdex" to 12.5),
                    "hists" to linkedMapOf<String, Any?>("personal" to linkedMapOf("안전" to 3), "userCnt" to 18),
                ),
                "nations" to linkedMapOf<String, Any?>(
                    "all" to listOf(linkedMapOf<String, Any?>("nation" to 1, "name" to "위", "power" to 1234)),
                ),
            ),
        )
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                statisticInserts = listOf(
                    StatisticInsertRow(linkedMapOf(
                        "year" to 181,
                        "month" to 1,
                        "nation_count" to 2,
                        "nation_name" to "위, 촉",
                        "nation_hist" to "위(10), 촉(8)",
                        "gen_count" to "18 (NPC 156)",
                        "personal_hist" to "안전(3)",
                        "special_hist" to "농업(2) // 귀병(1)",
                        "power_hist" to "위(1234)",
                        "crewtype" to "보병(5)",
                        "etc" to "etc",
                        "aux" to aux,
                    )),
                ),
            ),
        )

        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM statistic", MapSqlParameterSource(), Int::class.java,
        ))
        val row = jdbc.queryForMap(
            "SELECT year, month, nation_count, nation_name, aux::text AS aux_text FROM statistic WHERE year = 181",
            MapSqlParameterSource(),
        )
        assertEquals(181, row["year"])
        assertEquals(1, row["month"])
        assertEquals(2, row["nation_count"])
        assertEquals("위, 촉", row["nation_name"])
        // aux jsonb 컬럼 — Postgres jsonb는 키 순서/공백을 정규화하므로 비교는 decode-level.
        // (byte-order 보존은 MetaJson.encode 시점의 계약이고, at-rest 비교는 구조 동등으로 한다.)
        val auxText = row["aux_text"] as String
        assertEquals(MetaJson.decode(aux), MetaJson.decode(auxText))
        assertEquals(1234, jdbc.queryForObject(
            "SELECT (aux #>> '{nations,all,0,power}')::int FROM statistic WHERE year = 181",
            MapSqlParameterSource(), Int::class.java,
        ))
    }
}
