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
import kotlin.test.assertNull

/**
 * Phase 4X-A spec v3 §8 `RetainerFlushIT` — V55 DDL 위에서 step-8g 의 DELETE → CREATE → UPDATE 순서, 같은 payload 의
 * 「해제 → 같은 이름 서약」(UNIQUE 만족, N1), 지휘 부장 DELETE 뒤 `general_bugok.world_id` 보존 + commander NULL
 * (`ON DELETE SET NULL (commander_retainer_id)`, F3), 주인 general DELETE CASCADE, world_state meta 고수위 키
 * (값이 있을 때만) 를 실제 PostgreSQL 16 에서 증명한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetainerFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val worldId = opensamguk.common.world.WorldId(1)

    private fun ws(extra: Map<String, Any?> = emptyMap()) =
        linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1).apply { putAll(extra) }

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
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
        jdbc.update("INSERT INTO nation (world_id, id, name, color) VALUES (1, 1, '촉', '#000')", MapSqlParameterSource())
        for (id in listOf(10, 11)) {
            jdbc.update(
                "INSERT INTO general (world_id, id, name, nation_id, city_id, turn_time) VALUES (1, :id, :name, 1, 1, now())",
                MapSqlParameterSource().addValue("id", id).addValue("name", "장수$id"),
            )
        }
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun retainer(id: Int, master: Int = 10, name: String = "가신$id", relation: String = "lieutenant", loyalty: Int = 50) =
        RetainerRow(id, master, "RECRUITED", null, name, relation, "NONE", false, "MASTER_ONLY", loyalty, "none")

    private fun bugok(id: Int, master: Int = 10, commander: Int? = null, morale: Int = 50) =
        BugokRow(id, master, "부곡 $id", 300, 1100, 50, morale, 0, 0, commander)

    private fun count(table: String, where: String = "world_id = 1"): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", MapSqlParameterSource(), Int::class.java)!!

    @Test
    fun `8g order plus SET NULL column plus cascade plus meta high-water keys`() {
        // 1) 서약 2 + 편성 1(부장 1 지휘) — meta 키는 값이 있을 때만
        executor.flush(
            FlushPayload(
                worldId = worldId, worldStateUpdate = ws(mapOf("max_retainer_id" to 2, "max_bugok_id" to 1)),
                createdRetainers = listOf(retainer(1, name = "홍길동"), retainer(2, name = "임꺽정")),
                createdBugoks = listOf(bugok(1, commander = 1)),
            ),
        )
        assertEquals(2, count("general_retainers")); assertEquals(1, count("general_bugok"))
        val meta = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", MapSqlParameterSource(), String::class.java)!!
        assert(meta.contains("\"maxRetainerId\": 2") && meta.contains("\"maxBugokId\": 1")) { meta }

        // 2) 같은 payload: 홍길동 해제(DELETE) + 같은 이름 서약(CREATE) + 부곡 UPDATE(commander → 새 가신) — DELETE 가 앞이라 UNIQUE 만족
        executor.flush(
            FlushPayload(
                worldId = worldId, worldStateUpdate = ws(mapOf("max_retainer_id" to 3, "max_bugok_id" to 1)),
                deletedRetainerIds = listOf(1),
                createdRetainers = listOf(retainer(3, name = "홍길동")),
                updatedBugoks = listOf(bugok(1, commander = 3, morale = 56)),
            ),
        )
        assertEquals(2, count("general_retainers"))
        assertEquals(3, jdbc.queryForObject("SELECT commander_retainer_id FROM general_bugok WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Int::class.java))
        assertEquals(56, jdbc.queryForObject("SELECT morale FROM general_bugok WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Int::class.java))

        // 3) 지휘 부장(3) DELETE 만 — DB SET NULL (commander_retainer_id): world_id 보존, commander NULL, 행 유지
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedRetainerIds = listOf(3)))
        assertEquals(1, count("general_bugok"))
        assertNull(jdbc.queryForObject("SELECT commander_retainer_id FROM general_bugok WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Integer::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT world_id FROM general_bugok WHERE id = 1", MapSqlParameterSource(), Int::class.java))

        // 4) 주인 general(10) DELETE → 부모 CASCADE 로 가신·부곡 모두 사라진다(엔진은 pending 을 내지 않는다)
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedGenerals = listOf(10)))
        assertEquals(0, count("general_retainers", "world_id = 1 AND master_general_id = 10"))
        assertEquals(0, count("general_bugok", "world_id = 1 AND master_general_id = 10"))

        // 5) 행 0 payload 는 meta 고수위 키를 건드리지 않는다(값 그대로)
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws()))
        val meta2 = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", MapSqlParameterSource(), String::class.java)!!
        assert(meta2.contains("\"maxRetainerId\": 3")) { meta2 }
    }
}
