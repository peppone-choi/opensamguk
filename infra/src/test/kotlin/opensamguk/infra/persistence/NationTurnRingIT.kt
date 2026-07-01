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
 * FF2 — Testcontainers IT for the `nation_turn` ring buffer on [ReservedTurnRepository]
 * (MAX_CHIEF_TURNS = 12, keyed `(nation_id, officer_level, turn_idx)`).
 *
 * Mirrors the PHP `func_command.php` ring semantics (pull/push) re-pointed at the V1 +
 * V2-`brief` `nation_turn` table:
 *  - [ReservedTurnRepository.reserveNationTurn] upserts `(nation, officer_level, turn_idx mod 12)`,
 *  - [ReservedTurnRepository.readReservedNationTurn] reads it back (default `휴식`/`{}` when absent),
 *  - [ReservedTurnRepository.pullNationTurn] rotates: the run slot (turn_idx 0) vacates to
 *    `휴식`/`{}`/`brief 휴식` and the remaining slots shift down one (`func_command.php:140-169`).
 *
 * Plain JDBC only (no JPA/EntityManager).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NationTurnRingIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repo: ReservedTurnRepository

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
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
    fun `reserveNationTurn writes and readReservedNationTurn reads it back`() {
        repo.reserveNationTurn(nationId = 3, officerLevel = 12, turnIdx = 0, actionCode = "che_증축", argJson = """{"destCityID":5}""", brief = "증축")

        val reserved = repo.readReservedNationTurn(nationId = 3, officerLevel = 12, turnIdx = 0)
        assertEquals("che_증축", reserved.actionCode)
        assertEquals("""{"destCityID": 5}""", reserved.argJson)
        assertEquals("증축", reserved.brief)
        assertEquals(1, rowCount(3, 12, 0))
    }

    @Test
    fun `re-reserving the same nation+officer+turn_idx upserts in place`() {
        repo.reserveNationTurn(nationId = 4, officerLevel = 12, turnIdx = 2, actionCode = "che_감축", argJson = null)
        repo.reserveNationTurn(nationId = 4, officerLevel = 12, turnIdx = 2, actionCode = "che_천도", argJson = """{"destCityID":7}""", brief = "천도")

        assertEquals(1, rowCount(4, 12, 2))
        val reserved = repo.readReservedNationTurn(nationId = 4, officerLevel = 12, turnIdx = 2)
        assertEquals("che_천도", reserved.actionCode)
        assertEquals("천도", reserved.brief)
    }

    @Test
    fun `readReservedNationTurn of a never-written slot returns the default 휴식 entry`() {
        val reserved = repo.readReservedNationTurn(nationId = 99, officerLevel = 12, turnIdx = 4)
        assertEquals("휴식", reserved.actionCode)
        assertEquals("{}", reserved.argJson)
        assertEquals(0, rowCount(99, 12, 4))
    }

    @Test
    fun `pullNationTurn vacates slot 0 to 휴식 and shifts the remaining slots down one`() {
        // seed slots 0..2 with distinct actions for (nation 7, officer_level 12).
        repo.reserveNationTurn(nationId = 7, officerLevel = 12, turnIdx = 0, actionCode = "che_증축", argJson = """{"a":1}""", brief = "증축")
        repo.reserveNationTurn(nationId = 7, officerLevel = 12, turnIdx = 1, actionCode = "che_감축", argJson = """{"a":2}""", brief = "감축")
        repo.reserveNationTurn(nationId = 7, officerLevel = 12, turnIdx = 2, actionCode = "che_천도", argJson = """{"a":3}""", brief = "천도")

        repo.pullNationTurn(nationId = 7, officerLevel = 12)

        // slot 0 (was 증축) is now what was slot 1 (감축); slot 1 ← 천도; the run slot rotated to the
        // back as 휴식 (turn_idx 11).
        assertEquals("che_감축", repo.readReservedNationTurn(7, 12, 0).actionCode)
        assertEquals("che_천도", repo.readReservedNationTurn(7, 12, 1).actionCode)
        // the vacated slot rotated to the tail of the ring as 휴식/{}.
        val tail = repo.readReservedNationTurn(7, 12, 11)
        assertEquals("휴식", tail.actionCode)
        assertEquals("{}", tail.argJson)
        assertEquals("휴식", tail.brief)
    }

    @Test
    fun `pullNationTurn rotates a full ring without unique collisions`() {
        val nationId = 17
        seedFullNationRing(nationId, 12)

        repo.pullNationTurn(nationId = nationId, officerLevel = 12)

        assertEquals("cmd_1", repo.readReservedNationTurn(nationId, 12, 0).actionCode)
        assertEquals("cmd_11", repo.readReservedNationTurn(nationId, 12, 10).actionCode)
        assertEquals("휴식", repo.readReservedNationTurn(nationId, 12, 11).actionCode)
        assertEquals(ReservedTurnRepository.MAX_CHIEF_TURNS, totalRows(nationId, 12))
        assertEquals(0, outOfRangeRows(nationId, 12))
    }

    @Test
    fun `pushNationTurn rotates a full ring without unique collisions`() {
        val nationId = 18
        seedFullNationRing(nationId, 12)

        repo.pushNationTurn(nationId = nationId, officerLevel = 12, turnCnt = 1)

        assertEquals("휴식", repo.readReservedNationTurn(nationId, 12, 0).actionCode)
        assertEquals("cmd_0", repo.readReservedNationTurn(nationId, 12, 1).actionCode)
        assertEquals("cmd_10", repo.readReservedNationTurn(nationId, 12, 11).actionCode)
        assertEquals(ReservedTurnRepository.MAX_CHIEF_TURNS, totalRows(nationId, 12))
        assertEquals(0, outOfRangeRows(nationId, 12))
    }

    private fun rowCount(nationId: Int, officerLevel: Int, turnIdx: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM nation_turn WHERE nation_id = :n AND officer_level = :o AND turn_idx = :t",
            MapSqlParameterSource().addValue("n", nationId).addValue("o", officerLevel).addValue("t", turnIdx),
            Int::class.java,
        ) ?: 0

    private fun seedFullNationRing(nationId: Int, officerLevel: Int) {
        for (idx in 0 until ReservedTurnRepository.MAX_CHIEF_TURNS) {
            repo.reserveNationTurn(nationId, officerLevel, idx, actionCode = "cmd_$idx", brief = "brief_$idx")
        }
    }

    private fun totalRows(nationId: Int, officerLevel: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM nation_turn WHERE nation_id = :n AND officer_level = :o",
            MapSqlParameterSource().addValue("n", nationId).addValue("o", officerLevel),
            Int::class.java,
        ) ?: 0

    private fun outOfRangeRows(nationId: Int, officerLevel: Int): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM nation_turn
             WHERE nation_id = :n
               AND officer_level = :o
               AND (turn_idx < 0 OR turn_idx >= :max_chief)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("n", nationId)
                .addValue("o", officerLevel)
                .addValue("max_chief", ReservedTurnRepository.MAX_CHIEF_TURNS),
            Int::class.java,
        ) ?: 0
}
