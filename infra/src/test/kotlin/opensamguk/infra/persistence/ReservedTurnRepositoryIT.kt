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

/**
 * Testcontainers IT for [ReservedTurnRepository] (the `general_turn` ring buffer).
 *
 * Brings up `postgres:16-alpine`, applies the Flyway baseline, then asserts the TS
 * `reservedTurns.ts setGeneralTurn` semantics against the real `UNIQUE (general_id, turn_idx)`
 * table:
 *  - reserve `che_농지개간` at turn_idx 0 → readReserved returns it,
 *  - re-reserve the SAME (general, turn_idx) slot → upsert (exactly one row, latest action wins),
 *  - readReserved of a never-written idx → the default `휴식` entry, with arg `{}`.
 *
 * Plain JDBC only (no JPA/EntityManager) — the write path invariant the F4 guard enforces.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservedTurnRepositoryIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repo: ReservedTurnRepository

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
        repo = ReservedTurnRepository(jdbc)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `reserve writes turn_idx 0 and readReserved reads it back`() {
        repo.reserve(generalId = 10, turnIdx = 0, actionCode = "che_농지개간", argJson = """{"amount":100}""")

        val reserved = repo.readReserved(generalId = 10, turnIdx = 0)
        assertEquals("che_농지개간", reserved.actionCode)
        assertEquals("""{"amount": 100}""", reserved.argJson)

        // exactly one row for this (general, turn_idx).
        assertEquals(1, rowCount(generalId = 10, turnIdx = 0))
    }

    @Test
    fun `re-reserving the same general+turn_idx upserts in place (no duplicate)`() {
        repo.reserve(generalId = 11, turnIdx = 3, actionCode = "che_농지개간", argJson = null)
        // overwrite the same slot with a different action.
        repo.reserve(generalId = 11, turnIdx = 3, actionCode = "che_상업투자", argJson = """{"amount":50}""")

        // upsert, not append: still exactly one row.
        assertEquals(1, rowCount(generalId = 11, turnIdx = 3))

        val reserved = repo.readReserved(generalId = 11, turnIdx = 3)
        assertEquals("che_상업투자", reserved.actionCode)
        assertEquals("""{"amount": 50}""", reserved.argJson)
    }

    @Test
    fun `readReserved of a never-written idx returns the default 휴식 entry`() {
        val reserved = repo.readReserved(generalId = 99, turnIdx = 7)
        assertEquals(ReservedTurnRepository.DEFAULT_TURN_ACTION, reserved.actionCode)
        assertEquals("휴식", reserved.actionCode)
        assertEquals("{}", reserved.argJson)

        // a default read must NOT have created a row.
        assertEquals(0, rowCount(generalId = 99, turnIdx = 7))
    }

    private fun rowCount(generalId: Int, turnIdx: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE general_id = :g AND turn_idx = :t",
            MapSqlParameterSource().addValue("g", generalId).addValue("t", turnIdx),
            Int::class.java,
        ) ?: 0
}
