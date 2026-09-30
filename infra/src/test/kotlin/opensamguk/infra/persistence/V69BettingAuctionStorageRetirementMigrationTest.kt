package opensamguk.infra.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

class V69BettingAuctionStorageRetirementMigrationTest {
    @Test
    fun `retired storage disappears while unrelated world rows survive`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V69 migration IT skipped")
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            fun flyway(target: String) = Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                .target(MigrationVersion.fromVersion(target)).load()

            flyway("68").migrate()
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            jdbc.update("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) " +
                "VALUES (1, 'fixture', 200, 1, 3600)")
            jdbc.update("""INSERT INTO ng_auction
                (world_id, id, type, host_general_id, req_resource, open_date, close_date)
                VALUES (1, 3, 'buyRice', 7, 'gold', now(), now() + interval '1 hour')""")
            jdbc.update("""INSERT INTO ng_auction_bid
                (world_id, no, auction_id, general_id, amount, date)
                VALUES (1, 4, 3, 7, 10, now())""")
            jdbc.update("""INSERT INTO ng_betting
                (world_id, id, betting_id, general_id, betting_type, amount)
                VALUES (1, 5, 2, 7, '[1]', 10)""")
            jdbc.update("""INSERT INTO event (world_id, target_code, action)
                VALUES (1, 'global', '{"name":"OpenNationBetting"}'::jsonb),
                       (1, 'global', '{"name":"OtherAction"}'::jsonb)""")
            jdbc.update("""INSERT INTO game_kv (world_id, "table", namespace, key, value)
                VALUES (1, 'betting', 'id_2', 'master', '{}'::jsonb),
                       (1, 'game_env', 'global', 'last_betting_id', '2'::jsonb),
                       (1, 'game_env', 'global', 'current_turn', '7'::jsonb),
                       (NULL, 'inheritance', 'inheritance_7', 'point', '3'::jsonb)""")
            jdbc.update("""INSERT INTO command_inbox
                (world_id, request_id, payload_schema_version, command_kind, status,
                 intent_fingerprint, action_code, payload)
                VALUES (1, 'retired', 1, 'IMMEDIATE', 'ACCEPTED', 'retired',
                        'OpenNationBetting', '{"type":"OpenNationBetting"}'::jsonb),
                       (1, 'kept', 1, 'IMMEDIATE', 'ACCEPTED', 'kept',
                        'court.dispatch', '{"type":"ImmediateInput"}'::jsonb)""")
            jdbc.update("""INSERT INTO command_result
                (world_id, request_id, result_seq, terminal_status, result_type, ok,
                 committed_world_version, payload_schema_version, result_payload, sent_at)
                VALUES (1, 'retired', 1, 'APPLIED', 'APPLIED', true, 0, 1, '{}'::jsonb, now()),
                       (1, 'kept', 1, 'APPLIED', 'APPLIED', true, 0, 1, '{}'::jsonb, now())""")
            jdbc.update("""INSERT INTO command_outbox
                (world_id, event_id, request_id, event_type, payload_schema_version, payload)
                VALUES (1, 'retired-event', 'retired', 'commandResult', 1, '{}'::jsonb),
                       (1, 'kept-event', 'kept', 'commandResult', 1, '{}'::jsonb)""")

            flyway("69").migrate()

            for (name in listOf("ng_auction_bid", "ng_auction", "ng_betting")) {
                assertEquals(0, count(jdbc, "SELECT count(*) FROM pg_class WHERE oid = to_regclass('public.$name')"))
            }
            for (name in listOf("ng_auction_resource", "ng_auction_type")) {
                assertEquals(0, count(jdbc, "SELECT count(*) FROM pg_type WHERE typname = '$name'"))
            }
            assertEquals(0, count(jdbc, "SELECT count(*) FROM event WHERE action::text LIKE '%NationBetting%'"))
            assertEquals(1, count(jdbc, "SELECT count(*) FROM event WHERE action->>'name' = 'OtherAction'"))
            assertEquals(0, count(jdbc, """SELECT count(*) FROM game_kv WHERE "table"='betting'
                OR ("table"='game_env' AND key='last_betting_id')"""))
            assertEquals(2, count(jdbc, "SELECT count(*) FROM game_kv"))
            for (table in listOf("command_inbox", "command_result", "command_outbox")) {
                assertEquals(0, count(jdbc, "SELECT count(*) FROM $table WHERE request_id='retired'"))
                assertEquals(1, count(jdbc, "SELECT count(*) FROM $table WHERE request_id='kept'"))
            }
            for (name in listOf("city", "general", "game_event", "battle_session")) {
                assertEquals(1, count(jdbc, "SELECT count(*) FROM pg_class WHERE oid = to_regclass('public.$name')"))
            }
        }
    }

    private fun count(jdbc: JdbcTemplate, sql: String): Int =
        requireNotNull(jdbc.queryForObject(sql, Int::class.java))
}
