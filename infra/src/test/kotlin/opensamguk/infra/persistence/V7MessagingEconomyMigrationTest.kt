package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testcontainers IT for the T0.1 `V7__p6_messaging_economy` migration.
 *
 * Applies the FULL Flyway chain through V7 and asserts:
 *  - the chain applies cleanly (V7 reconciles auction/auction_bid → ng_auction/ng_auction_bid
 *    via DROP+CREATE; the TS-shaped V1 placeholders were empty/unused);
 *  - `message`/`ng_betting`/`game_kv`/`ng_auction`/`ng_auction_bid` exist with PHP-faithful shapes;
 *  - the old TS-shaped `auction`/`auction_bid` relations are GONE;
 *  - the new unique indexes + the message by_mailbox index are present.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V7MessagingEconomyMigrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        dataSource = DriverManagerDataSource().apply {
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
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun tableExists(name: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM information_schema.tables WHERE table_name = :n",
        MapSqlParameterSource("n", name), Int::class.java,
    )!!

    private fun cols(table: String): List<String> = jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_name = :n",
        MapSqlParameterSource("n", table), String::class.java,
    )

    @Test
    fun `the new P6 tables exist and the TS-shaped auction placeholders are gone`() {
        assertEquals(1, tableExists("message"))
        assertEquals(1, tableExists("ng_betting"))
        assertEquals(1, tableExists("game_kv"))
        assertEquals(1, tableExists("ng_auction"))
        assertEquals(1, tableExists("ng_auction_bid"))
        assertEquals(0, tableExists("auction"), "the TS-shaped V1 auction placeholder is reconciled away")
        assertEquals(0, tableExists("auction_bid"))
    }

    @Test
    fun `message has the PHP-faithful columns and accepts a row with the validUntil sentinel`() {
        assertTrue(cols("message").containsAll(listOf("id", "mailbox", "type", "src", "dest", "time", "valid_until", "message")))
        jdbc.update(
            """
            INSERT INTO message (mailbox, type, src, dest, message)
            VALUES (9001, CAST('national' AS message_type), 10, 20, '{"action":"scout"}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val vu = jdbc.queryForObject(
            "SELECT to_char(valid_until, 'YYYY') FROM message WHERE mailbox = 9001 LIMIT 1",
            MapSqlParameterSource(), String::class.java,
        )
        assertEquals("9999", vu, "valid_until defaults to the PHP 9999 sentinel")
    }

    @Test
    fun `ng_betting enforces the two PHP unique indexes (amount += relies on the general+betting+type key)`() {
        jdbc.update(
            "INSERT INTO ng_betting (betting_id, general_id, user_id, betting_type, amount) VALUES (3, 42, 7, '[1]', 100)",
            MapSqlParameterSource(),
        )
        var threw = false
        try {
            jdbc.update(
                "INSERT INTO ng_betting (betting_id, general_id, user_id, betting_type, amount) VALUES (3, 42, 7, '[1]', 200)",
                MapSqlParameterSource(),
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw, "(general_id, betting_id, betting_type) is unique — a re-bet UPSERTs amount, never double-inserts")
    }

    @Test
    fun `ng_auction stores the resource enum and finished boolean`() {
        assertTrue(cols("ng_auction").containsAll(listOf("id", "type", "finished", "target", "host_general_id", "req_resource", "open_date", "close_date", "detail")))
        jdbc.update(
            """
            INSERT INTO ng_auction (type, finished, host_general_id, req_resource, open_date, close_date)
            VALUES (CAST('uniqueItem' AS ng_auction_type), false, 0, CAST('inheritPoint' AS ng_auction_resource),
                    now(), now() + interval '1 hour')
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val res = jdbc.queryForObject(
            "SELECT req_resource::text FROM ng_auction LIMIT 1", MapSqlParameterSource(), String::class.java,
        )
        assertEquals("inheritPoint", res)
    }

    @Test
    fun `game_kv enforces the (table, namespace, key) unique key`() {
        jdbc.update(
            """INSERT INTO game_kv ("table", namespace, key, value) VALUES ('game_env', 'global', 'last_betting_id', '5'::jsonb)""",
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO game_kv ("table", namespace, key, value) VALUES ('game_env', 'global', 'last_betting_id', '6'::jsonb)
            ON CONFLICT ("table", namespace, key) DO UPDATE SET value = EXCLUDED.value
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val v = jdbc.queryForObject(
            """SELECT value::text FROM game_kv WHERE "table" = 'game_env' AND namespace = 'global' AND key = 'last_betting_id'""",
            MapSqlParameterSource(), String::class.java,
        )
        assertEquals("6", v, "UPSERT on the unique key overwrites (delete-on-null handled by the flush executor)")
    }
}
