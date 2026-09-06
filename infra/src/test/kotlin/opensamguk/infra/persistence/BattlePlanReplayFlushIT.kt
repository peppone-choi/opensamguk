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
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * spec v4.1 §8 `BattlePlanReplayFlushIT`(PG16) — V57 DDL(부분 UNIQUE 인덱스) · 8i 순서(계획 → 리플레이) · 리플레이 INSERT ·
 * 「봉인 출병 → 같은 틱 사망」 payload(attacker/plan NULL) 가 5단계 general DELETE 와 같은 flush 에서 COMMIT(F3 적색면) ·
 * 「작전 연결 리플레이 + 같은 flush 의 nation cascade DELETE」 → operation_id NULL 로 COMMIT(N4 적색면) · 소비된 계획 뒤 같은 키 재생성.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BattlePlanReplayFlushIT {

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

    private fun plan(id: Int, generalId: Int = 10, sealed: Boolean = true, resolved: Boolean = false, version: Int = 1) = BattlePlanRow(
        id, generalId, 2, "probe", 30, null,
        if (sealed) Instant.parse("2026-09-06T12:00:00Z") else null, if (sealed) 200 else null, if (sealed) 1 else null, if (sealed) 1 else null,
        if (resolved) 200 else null, if (resolved) 1 else null, if (resolved) 2 else null, version,
    )

    private fun replay(planId: Int?, attackerId: Int?, operationId: Int? = null) = BattleReplayInsertRow(
        linkedMapOf(
            "id" to 1, "battle_plan_id" to planId, "operation_id" to operationId, "attacker_general_id" to attackerId, "attacker_name" to "장수10", "attacker_nation_id" to 1,
            "defender_city_id" to 2, "defender_city_name" to "허창", "defender_nation_id" to 2, "year" to 200, "month" to 1, "phase" to 2,
            "war_seed" to "0".repeat(32), "input_hash" to "a".repeat(64), "replay_hash" to "b".repeat(64), "schema_version" to 1,
            "battle_phases_json" to "{\"phases\":[],\"stop\":{\"atPhase\":null,\"kind\":null},\"v\":1}",
            "attacker_crew_before" to 1000, "attacker_crew_after" to 900, "attacker_dead" to 100, "defender_dead" to 50, "rice_used" to 10,
            "result" to "retreat", "plan_stop" to "probe", "plan_stance" to "probe", "plan_retreat_loss_pct" to 30, "plan_retreat_morale_below" to null,
        ),
    )

    private fun count(table: String, where: String = "world_id = 1"): Int = jdbc.queryForObject("SELECT count(*) FROM $table WHERE $where", MapSqlParameterSource(), Int::class.java)!!

    @Test
    fun `partial unique, same-tick plan plus replay, death and nation cascade red faces, consumed key reuse`() {
        // 1) 계획 CREATE + 봉인 UPDATE(다른 flush) + 리플레이 INSERT(같은 flush, 8i 순서: 계획 뒤)
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(mapOf("max_battle_plan_id" to 1)), createdBattlePlans = listOf(plan(1, sealed = false))))
        assertEquals(1, count("battle_plan")); assertNull(jdbc.queryForObject("SELECT sealed_at FROM battle_plan WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), java.sql.Timestamp::class.java))
        val meta = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", MapSqlParameterSource(), String::class.java)!!
        assert(meta.contains("\"maxBattlePlanId\": 1")) { meta }
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), updatedBattlePlans = listOf(plan(1, sealed = true, resolved = true, version = 2)), battleReplayInserts = listOf(replay(1, 10))))
        assertEquals(1, count("battle_replay")); assertEquals(2, jdbc.queryForObject("SELECT version FROM battle_plan WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Int::class.java))
        assertEquals("probe", jdbc.queryForObject("SELECT plan_stop FROM battle_replay WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), String::class.java))

        // 2) 부분 UNIQUE: 소비된 1번과 같은 키(10, 2)의 새 미소비 계획은 허용, 미소비 둘은 거부(적색면)
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(mapOf("max_battle_plan_id" to 2)), createdBattlePlans = listOf(plan(2, sealed = false))))
        assertEquals(2, count("battle_plan"))
        assertThrows<Exception> { executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), createdBattlePlans = listOf(plan(3, sealed = false)))) }
        assertEquals(2, count("battle_plan"))

        // 3) F3 적색면: 「봉인 출병 → 같은 틱 사망」 — 5단계 general DELETE(계획 2 CASCADE) 와 같은 flush 의 리플레이 INSERT 는
        //    recorder 가 attacker/plan 을 NULL 로 바꾼 payload 라 COMMIT 된다; NULL 로 바꾸지 않은 payload 는 FK 로 터진다.
        assertThrows<Exception> { executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedGenerals = listOf(10), battleReplayInserts = listOf(BattleReplayInsertRow(LinkedHashMap(replay(2, 10).columns).apply { put("id", 2) })))) }
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedGenerals = listOf(10), battleReplayInserts = listOf(BattleReplayInsertRow(LinkedHashMap(replay(null, null).columns).apply { put("id", 2) }))))
        assertEquals(2, count("battle_replay")); assertEquals(0, count("battle_plan"), "장수 CASCADE 로 계획이 사라진다")
        assertNull(jdbc.queryForObject("SELECT attacker_general_id FROM battle_replay WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), Integer::class.java), "SET NULL (attacker_general_id)")
        assertEquals("장수10", jdbc.queryForObject("SELECT attacker_name FROM battle_replay WHERE world_id = 1 AND id = 1", MapSqlParameterSource(), String::class.java))

        // 4) N4 적색면: 작전(국가 1) 연결 리플레이 + 같은 flush 의 nation 1 cascade DELETE — operation_id NULL 이면 COMMIT, 아니면 실패
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(mapOf("max_operation_id" to 1)), createdOperations = listOf(OperationRow(1, 1, "capture_city", 2, "허창 공략", null, 11, 200, 1, 1, 200, 3, 1, "active", false, false, false, false, null))))
        assertThrows<Exception> { executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedNations = listOf(1), battleReplayInserts = listOf(BattleReplayInsertRow(LinkedHashMap(replay(null, 11, operationId = 1).columns).apply { put("id", 3) })))) }
        executor.flush(FlushPayload(worldId = worldId, worldStateUpdate = ws(), deletedNations = listOf(1), battleReplayInserts = listOf(BattleReplayInsertRow(LinkedHashMap(replay(null, 11, operationId = null).columns).apply { put("id", 3) }))))
        assertEquals(3, count("battle_replay")); assertEquals(0, count("operation"))
        assertEquals(1, jdbc.queryForObject("SELECT attacker_nation_id FROM battle_replay WHERE world_id = 1 AND id = 3", MapSqlParameterSource(), Int::class.java), "국가 id 는 FK 없는 스냅샷")
    }
}
