package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
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
 * spec v4.1 §8 `OperationFlushIT` — V56 DDL(`SET NULL (col)` + `DEFERRABLE INITIALLY DEFERRED` 조합 핀) · 8h 순서(8g 뒤) ·
 * 같은 틱 「선언 + 연결 글」 성공(N1) · 존재하지 않는 operation_id 의 글은 COMMIT 에서 실패(R5 적색면) · 선언자 DELETE 와
 * 같은 틱 작전 UPDATE → world_id 보존·declared_by NULL(F2) · bugok DELETE → unit bugok_id NULL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val worldId = opensamguk.common.world.WorldId(1)

    private fun ws(extra: Map<String, Any?> = emptyMap()) = linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1).apply { putAll(extra) }

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply { setDriverClassName("org.postgresql.Driver"); url = postgres.jdbcUrl; username = postgres.username; password = postgres.password }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)", MapSqlParameterSource())
        jdbc.update("INSERT INTO nation (world_id, id, name, color) VALUES (1, 1, '촉', '#000'), (1, 2, '위', '#00f')", MapSqlParameterSource())
        for ((id, name, nation) in listOf(Triple(1, "낙양", 1), Triple(2, "허창", 2))) {
            jdbc.update(
                """
                INSERT INTO city
                    (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max, agri, agri_max, comm, comm_max,
                     secu, secu_max, trust, trade, def, def_max, wall, wall_max, region, meta)
                VALUES
                    (1, :id, :name, 5, :nation, 1, 0, 50000, 100000, 1000, 2000, 800, 2000, 500, 1000, 50, 100, 1000, 2000, 1000, 2000, 1, CAST('{}' AS jsonb))
                """.trimIndent(),
                MapSqlParameterSource().addValue("id", id).addValue("name", name).addValue("nation", nation),
            )
        }
        jdbc.update("INSERT INTO general (world_id, id, name, nation_id, city_id, turn_time) VALUES (1, 10, '장수10', 1, 1, now()), (1, 11, '장수11', 1, 1, now())", MapSqlParameterSource())
    }

    @AfterAll fun tearDown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun op(id: Int, declaredBy: Int? = 10, status: String = "declared", departed: Boolean = false) =
        OperationRow(id, 1, "capture_city", 2, "허창 공략", null, declaredBy, 200, 1, 1, 200, 3, 1, status, departed, false, false, false, null)

    private fun unit(id: Int, opId: Int, generalId: Int, bugokId: Int? = null) = OperationUnitRow(id, opId, generalId, bugokId, "main", 1, 200, 1, 1)

    private fun count(table: String, where: String = "world_id = 1"): Int = jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", MapSqlParameterSource(), Int::class.java)!!

    @Test
    fun `same tick declare plus linked board post commits, bugok delete nulls unit, declarer delete keeps world_id`() {
        // 1) 부곡(8g) + 선언(8h) + unit(bugok 연결) + 연결 글(8d, DEFERRABLE) — 한 payload
        executor.flush(
            FlushPayload(
                worldId = worldId, worldStateUpdate = ws(mapOf("max_operation_id" to 1, "max_operation_unit_id" to 1, "max_bugok_id" to 5)),
                createdBugoks = listOf(BugokRow(5, 11, "부곡 5", 100, 1100, 50, 50, 0, 0, null)),
                createdOperations = listOf(op(1)),
                createdOperationUnits = listOf(unit(1, 1, 11, bugokId = 5)),
                boardPostInserts = listOf(BoardPostInsertRow(linkedMapOf("nation_id" to 1, "is_secret" to false, "author_general_id" to 10, "author_name" to "장수10", "title" to "회의", "content_html" to "본문", "kind" to "operation", "operation_id" to 1))),
            ),
        )
        assertEquals(1, count("operation")); assertEquals(1, count("operation_unit")); assertEquals(1, count("board_post", "world_id = 1 AND operation_id = 1"))
        val meta = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", MapSqlParameterSource(), String::class.java)!!
        assert(meta.contains("\"maxOperationId\": 1")) { meta }

        // 2) 적색면(R5): 존재하지 않는 operation_id 를 단 글은 COMMIT 에서 실패한다(deferred FK 가 살아 있다)
        assertThrows<Exception> {
            executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), boardPostInserts = listOf(BoardPostInsertRow(linkedMapOf("nation_id" to 1, "is_secret" to false, "author_general_id" to 10, "author_name" to "장수10", "title" to "x", "content_html" to "y", "kind" to "operation", "operation_id" to 999)))))
        }
        assertEquals(1, count("board_post", "world_id = 1 AND kind = 'operation'"))

        // 3) 부곡 DELETE(8g) → unit.bugok_id NULL(SET NULL (bugok_id)), world_id 보존
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedBugokIds = listOf(5)))
        assertNull(jdbc.queryForObject("SELECT bugok_id FROM operation_unit WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Integer::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT world_id FROM operation_unit WHERE id = 1", MapSqlParameterSource(), Int::class.java))

        // 4) 선언자 general DELETE(5단계) 와 같은 틱 작전 UPDATE(8h, declared_by NULL 로 실린 행) → 성공, world_id 보존
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedGenerals = listOf(10), updatedOperations = listOf(op(1, declaredBy = null, status = "active", departed = true))))
        assertNull(jdbc.queryForObject("SELECT declared_by_general_id FROM operation WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Integer::class.java))
        assertEquals("active", jdbc.queryForObject("SELECT status FROM operation WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), String::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT world_id FROM operation WHERE id = 1", MapSqlParameterSource(), Int::class.java))

        // 5) 국가 CASCADE: nation 2 는 작전이 없고, nation 1 삭제(6단계) → 작전·unit·글의 operation_id(SET NULL) 정리
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedNations = listOf(1)))
        assertEquals(0, count("operation")); assertEquals(0, count("operation_unit"))
    }
}
