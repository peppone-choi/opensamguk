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
 * T0.3 — Testcontainers IT proving the generalized KV channel writes BOTH stores end-to-end:
 *  - int-namespace `nation_env` (V3),
 *  - string-namespace `game_kv` (V7, with the `table` discriminator + raw-json passthrough),
 *  with delete-on-null on both. This is the round-trip that confirms a string-namespace KV delta
 *  actually reaches the DB through the JdbcFlushExecutor (previously there was no home for it).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameKvFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))

        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
        // pre-seed a stale game_kv row to exercise delete-on-null.
        jdbc.update(
            """INSERT INTO game_kv ("table", namespace, key, value) VALUES ('betting', 'id_3', 'stale', '1'::jsonb)""",
            MapSqlParameterSource(),
        )
        // pre-seed a stale nation_env row to exercise delete-on-null there too.
        jdbc.update(
            "INSERT INTO nation_env (namespace, key, value) VALUES (5, 'stale', '1'::jsonb)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `kv flush writes game_kv string-ns and nation_env int-ns with delete-on-null`() {
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                kvWrites = listOf(
                    // string-ns game_kv: a raw-json String value bound verbatim (PHP json_encode bytes).
                    KvWrite("game_env", "global", "obfuscatedNamePool", """["가","나"]"""),
                    // string-ns game_kv: an Int value MetaJson-encoded at flush.
                    KvWrite("game_env", "global", "last_betting_id", 7),
                    // string-ns game_kv delete-on-null.
                    KvWrite("betting", "id_3", "stale", null),
                    // int-ns nation_env via the same channel.
                    KvWrite.nationEnv(namespace = 5, key = "nationNotice", value = mapOf("text" to "공지")),
                    KvWrite.nationEnv(namespace = 5, key = "stale", value = null),
                    // V15/P0-07 — inheritance 채널: "table" 판별자 = storage 이름 'inheritance'
                    // (ChangeRecorder.recordInheritancePointSet 출력 shape). reader
                    // (InheritanceRepository: "table"='inheritance')와 같은 행에 착지해야 한다.
                    KvWrite("inheritance", "inheritance_77", "previous", listOf(500.0, null)),
                ),
            ),
        )

        // game_env string-ns rows present. (We compare jsonb-semantically — Postgres `::text`
        // re-pretty-prints jsonb with spaces after commas, a DISPLAY artifact, not a storage
        // difference; the byte-faithful PHP json_encode form is asserted by the daemon golden gate.)
        assertEquals(
            1,
            jdbc.queryForObject(
                """SELECT count(*) FROM game_kv WHERE "table"='game_env' AND namespace='global'
                   AND key='obfuscatedNamePool' AND value = '["가","나"]'::jsonb""".trimIndent(),
                MapSqlParameterSource(), Int::class.java,
            ),
            "the raw-json passthrough value is stored (jsonb-equal to the PHP json_encode bytes)",
        )
        assertEquals(
            "7",
            jdbc.queryForObject(
                """SELECT value::text FROM game_kv WHERE "table"='game_env' AND namespace='global' AND key='last_betting_id'""",
                MapSqlParameterSource(), String::class.java,
            ),
        )
        // delete-on-null removed the stale betting row.
        assertEquals(
            0,
            jdbc.queryForObject(
                """SELECT count(*) FROM game_kv WHERE "table"='betting' AND namespace='id_3' AND key='stale'""",
                MapSqlParameterSource(), Int::class.java,
            ),
        )
        // nation_env int-ns set + delete-on-null.
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM nation_env WHERE namespace=5 AND key='nationNotice' AND value = '{\"text\":\"공지\"}'::jsonb",
                MapSqlParameterSource(), Int::class.java,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM nation_env WHERE namespace=5 AND key='stale'",
                MapSqlParameterSource(), Int::class.java,
            ),
        )
        // inheritance 채널 라운드트립 — reader(InheritanceRepository)가 조회하는 정확한
        // ("table"='inheritance', namespace, key) 좌표에 착지했는지("table"='game_kv' 오기록이면 0행).
        assertEquals(
            1,
            jdbc.queryForObject(
                """SELECT count(*) FROM game_kv WHERE "table"='inheritance'
                   AND namespace='inheritance_77' AND key='previous'""".trimIndent(),
                MapSqlParameterSource(), Int::class.java,
            ),
            "데몬 inheritance KV 쓰기는 'inheritance' 판별자로 착지해야 reader와 만난다 (V15)",
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                """SELECT count(*) FROM game_kv WHERE "table"='game_kv'""",
                MapSqlParameterSource(), Int::class.java,
            ),
            "물리 테이블명 'game_kv'가 판별자로 오기록되면 안 된다",
        )
    }
}
