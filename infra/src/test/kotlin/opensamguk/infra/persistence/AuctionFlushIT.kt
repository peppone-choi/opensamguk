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
 * T0.7 — Testcontainers IT for the auction channel: ng_auction UPSERT (open INSERT with the
 * pre-assigned id, then finish UPDATE) + ng_auction_bid INSERT (two outbid rows, NEITHER deleted).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuctionFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

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
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `auction open INSERT + two outbid bids (no delete) + finish UPDATE`() {
        // open (INSERT, allocatedId 1) + two bids (INSERT-only, outbid row persists).
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                auctionUpserts = listOf(
                    AuctionUpsertRow(id = null, allocatedId = 1, columns = linkedMapOf(
                        "type" to "uniqueItem", "finished" to false, "target" to "che_보검",
                        "host_general_id" to 0, "req_resource" to "inheritPoint",
                        "open_date" to "2026-05-31 00:00:00", "close_date" to "2026-05-31 01:00:00",
                        "detail" to """{"title":"보검","hostName":"상인","amount":5000,"startBidAmount":5000}""",
                    )),
                ),
                auctionBidInserts = listOf(
                    AuctionBidInsertRow(linkedMapOf("auction_id" to 1, "owner" to null, "general_id" to 10,
                        "amount" to 5000, "date" to "2026-05-31 00:10:00", "aux" to """{"generalName":"관우"}""")),
                    AuctionBidInsertRow(linkedMapOf("auction_id" to 1, "owner" to null, "general_id" to 20,
                        "amount" to 6060, "date" to "2026-05-31 00:20:00", "aux" to """{"generalName":"장비","tryExtendCloseDate":true}""")),
                ),
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ng_auction", MapSqlParameterSource(), Int::class.java))
        // BOTH bids persist — the outbid 5000 row is NOT deleted.
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ng_auction_bid WHERE auction_id = 1", MapSqlParameterSource(), Int::class.java))
        assertEquals("inheritPoint", jdbc.queryForObject("SELECT req_resource::text FROM ng_auction WHERE id = 1", MapSqlParameterSource(), String::class.java))

        // finish (UPDATE on the same id): finished -> true.
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                auctionUpserts = listOf(
                    AuctionUpsertRow(id = 1, allocatedId = null, columns = linkedMapOf(
                        "type" to "uniqueItem", "finished" to true, "target" to "che_보검",
                        "host_general_id" to 0, "req_resource" to "inheritPoint",
                        "open_date" to "2026-05-31 00:00:00", "close_date" to "2026-05-31 01:00:00",
                        "detail" to """{"title":"보검","hostName":"상인","amount":5000,"startBidAmount":5000}""",
                    )),
                ),
            ),
        )
        assertEquals(true, jdbc.queryForObject("SELECT finished FROM ng_auction WHERE id = 1", MapSqlParameterSource(), Boolean::class.java))
        // still 2 bids (finish does not delete them).
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ng_auction_bid WHERE auction_id = 1", MapSqlParameterSource(), Int::class.java))
    }
}
