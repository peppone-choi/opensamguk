package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
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
    private val worldId = WorldId(1)
    private val otherWorldId = WorldId(2)

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
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
        repo = ReservedTurnRepository(jdbc)
        seedWorld(worldId)
        seedWorld(otherWorldId)
        seedGenerals(worldId, 10, 11, 20, 99, 119, 120, 121, 122, 501)
        seedGenerals(otherWorldId, 501)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `original reservation owner survives slot shift and terminal inbox status`() {
        seedGenerals(worldId, 750)
        val requestId = "owner-shift-750"
        insertOwnerInbox(worldId, requestId, 750)
        repo.reserve(worldId, 750, 1, "action.deploy", "{}", requestId = requestId)
        repo.pullGeneralTurn(worldId, 750)
        jdbc.update("UPDATE command_inbox SET status='APPLIED' WHERE world_id=1 AND request_id=:request",
            mapOf("request" to requestId))
        assertEquals(42, repo.readReserved(worldId, 750, 0).reservationOwnerUserId)
    }

    @Test
    fun `unrelated inbox headers cannot supply reservation ownership`() {
        seedGenerals(worldId, 751)
        val cases = listOf(
            Triple(otherWorldId, 751, CommandInboxRepository.CommandKind.RESERVED_TURN),
            Triple(worldId, 750, CommandInboxRepository.CommandKind.RESERVED_TURN),
            Triple(worldId, 751, CommandInboxRepository.CommandKind.IMMEDIATE),
        )
        cases.forEachIndexed { index, (inboxWorld, general, kind) ->
            val request = "wrong-owner-$index"
            insertOwnerInbox(inboxWorld, request, general, kind)
            repo.reserve(worldId, 751, 0, "action.deploy", "{}", requestId = request)
            assertEquals(null, repo.readReserved(worldId, 751, 0).reservationOwnerUserId)
        }
        insertOwnerInbox(worldId, "wrong-action", 751, action = "action.enlist")
        repo.reserve(worldId, 751, 0, "action.deploy", "{}", requestId = "wrong-action")
        assertEquals(null, repo.readReserved(worldId, 751, 0).reservationOwnerUserId)
    }

    private fun insertOwnerInbox(
        world: WorldId, request: String, general: Int,
        kind: CommandInboxRepository.CommandKind = CommandInboxRepository.CommandKind.RESERVED_TURN,
        action: String = "action.deploy",
    ) {
        val envelope = opensamguk.common.wire.TurnDaemonCommandEnvelope(request, "0200-01-01T00:00:00Z",
            opensamguk.common.wire.TurnDaemonCommand.Run(opensamguk.common.wire.RunReason.POKE))
        CommandInboxRepository(jdbc).insertAccepted(CommandInboxRepository.AcceptedCommand(
            world, request, commandKind = kind, intentFingerprint = "a".repeat(64), generalId = general,
            turnIdx = 1, actionCode = action, payloadJson = opensamguk.common.wire.encodeCommandPayload(envelope),
            ownerUserId = 42,
        ))
    }

    @Test
    fun `reserve writes turn_idx 0 and readReserved reads it back`() {
        repo.reserve(worldId = worldId, generalId = 10, turnIdx = 0, actionCode = "che_농지개간", argJson = """{"amount":100}""")

        val reserved = repo.readReserved(worldId = worldId, generalId = 10, turnIdx = 0)
        assertEquals("che_농지개간", reserved.actionCode)
        assertEquals("""{"amount": 100}""", reserved.argJson)

        // exactly one row for this (general, turn_idx).
        assertEquals(1, rowCount(worldId = worldId, generalId = 10, turnIdx = 0))
    }

    @Test
    fun `re-reserving the same general+turn_idx upserts in place (no duplicate)`() {
        repo.reserve(worldId = worldId, generalId = 11, turnIdx = 3, actionCode = "che_농지개간", argJson = null)
        // overwrite the same slot with a different action.
        repo.reserve(worldId = worldId, generalId = 11, turnIdx = 3, actionCode = "che_상업투자", argJson = """{"amount":50}""")

        // upsert, not append: still exactly one row.
        assertEquals(1, rowCount(worldId = worldId, generalId = 11, turnIdx = 3))

        val reserved = repo.readReserved(worldId = worldId, generalId = 11, turnIdx = 3)
        assertEquals("che_상업투자", reserved.actionCode)
        assertEquals("""{"amount": 50}""", reserved.argJson)
    }

    @Test
    fun `reserve writes the V2 brief column and readReserved reads it back`() {
        // FD1: PHP seeds general_turn.brief '휴식' on every row (GeneralBuilder.php:720).
        // reserve without an explicit brief defaults to 휴식.
        repo.reserve(worldId = worldId, generalId = 20, turnIdx = 0, actionCode = "che_농지개간", argJson = null)
        assertEquals("휴식", repo.readReserved(worldId = worldId, generalId = 20, turnIdx = 0).brief)

        // an explicit brief round-trips and an upsert overwrites it in place.
        repo.reserve(worldId = worldId, generalId = 20, turnIdx = 0, actionCode = "che_상업투자", argJson = null, brief = "상업에 투자하였습니다.")
        val reserved = repo.readReserved(worldId = worldId, generalId = 20, turnIdx = 0)
        assertEquals("che_상업투자", reserved.actionCode)
        assertEquals("상업에 투자하였습니다.", reserved.brief)
        assertEquals(1, rowCount(worldId = worldId, generalId = 20, turnIdx = 0))
    }

    @Test
    fun `readReserved of a never-written idx returns the default 휴식 entry`() {
        val reserved = repo.readReserved(worldId = worldId, generalId = 99, turnIdx = 7)
        assertEquals(ReservedTurnRepository.DEFAULT_TURN_ACTION, reserved.actionCode)
        assertEquals("휴식", reserved.actionCode)
        assertEquals("{}", reserved.argJson)

        assertEquals(false, reserved.rowExists)

        // a default read must NOT have created a row.
        assertEquals(0, rowCount(worldId = worldId, generalId = 99, turnIdx = 7))
    }

    @Test
    fun `explicit rest row without request id remains distinguishable from absence`() {
        val generalId = 119
        assertEquals(false, repo.readReserved(worldId, generalId, 0).rowExists)
        repo.reserve(worldId, generalId, 0, "휴식", "{}")
        val stored = repo.readReserved(worldId, generalId, 0)
        assertEquals(true, stored.rowExists)
        assertEquals(null, stored.requestId)
        assertEquals("휴식", stored.actionCode)
        assertEquals("{}", stored.argJson)
    }

    @Test
    fun `pullGeneralTurn rotates a full ring without unique collisions`() {
        val generalId = 120
        seedFullGeneralRing(generalId)

        repo.pullGeneralTurn(worldId, generalId)

        assertEquals("cmd_1", repo.readReserved(worldId, generalId, 0).actionCode)
        assertEquals("cmd_29", repo.readReserved(worldId, generalId, 28).actionCode)
        assertEquals("휴식", repo.readReserved(worldId, generalId, 29).actionCode)
        assertEquals(ReservedTurnRepository.MAX_GENERAL_TURNS, totalRows(worldId, generalId))
        assertEquals(0, outOfRangeRows(worldId, generalId))
    }

    @Test
    fun `pullGeneralTurn completes a legacy half-rotated tail row`() {
        val generalId = 121
        seedFullGeneralRing(generalId)
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = :max_turn,
                   action_code = '휴식',
                   arg = '{}'::jsonb,
                   brief = '휴식'
             WHERE general_id = :g AND turn_idx = 0
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("g", generalId)
                .addValue("max_turn", ReservedTurnRepository.MAX_GENERAL_TURNS),
        )

        repo.pullGeneralTurn(worldId, generalId)

        assertEquals("cmd_1", repo.readReserved(worldId, generalId, 0).actionCode)
        assertEquals("cmd_29", repo.readReserved(worldId, generalId, 28).actionCode)
        assertEquals("휴식", repo.readReserved(worldId, generalId, 29).actionCode)
        assertEquals(ReservedTurnRepository.MAX_GENERAL_TURNS, totalRows(worldId, generalId))
        assertEquals(0, outOfRangeRows(worldId, generalId))
    }

    @Test
    fun `pushGeneralTurn rotates a full ring without unique collisions`() {
        val generalId = 122
        seedFullGeneralRing(generalId)

        repo.pushGeneralTurn(worldId, generalId, turnCnt = 1)

        assertEquals("휴식", repo.readReserved(worldId, generalId, 0).actionCode)
        assertEquals("cmd_0", repo.readReserved(worldId, generalId, 1).actionCode)
        assertEquals("cmd_28", repo.readReserved(worldId, generalId, 29).actionCode)
        assertEquals(ReservedTurnRepository.MAX_GENERAL_TURNS, totalRows(worldId, generalId))
        assertEquals(0, outOfRangeRows(worldId, generalId))
    }

    @Test
    fun `general turn upsert read and rotation do not touch the same key in another world`() {
        val generalId = 501
        repo.reserve(worldId, generalId, 0, "world-one")
        repo.reserve(otherWorldId, generalId, 0, "world-two")

        repo.pullGeneralTurn(worldId, generalId)

        assertEquals("휴식", repo.readReserved(worldId, generalId, 29).actionCode)
        assertEquals("world-two", repo.readReserved(otherWorldId, generalId, 0).actionCode)
        assertEquals(1, rowCount(otherWorldId, generalId, 0))
    }

    private fun seedWorld(worldId: WorldId) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta)
            VALUES (:id, 'scenario_1010', 181, 1, 3600, '{}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", worldId.value),
        )
    }

    private fun seedGenerals(worldId: WorldId, vararg generalIds: Int) {
        for (generalId in generalIds) {
            jdbc.update(
                """
                INSERT INTO general (world_id, id, name, turn_time)
                VALUES (:world_id, :id, :name, now())
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("id", generalId)
                    .addValue("name", "general-$generalId"),
            )
        }
    }

    private fun rowCount(worldId: WorldId, generalId: Int, turnIdx: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE world_id = :w AND general_id = :g AND turn_idx = :t",
            MapSqlParameterSource().addValue("w", worldId.value).addValue("g", generalId).addValue("t", turnIdx),
            Int::class.java,
        ) ?: 0

    private fun seedFullGeneralRing(generalId: Int) {
        for (idx in 0 until ReservedTurnRepository.MAX_GENERAL_TURNS) {
            repo.reserve(worldId = worldId, generalId = generalId, turnIdx = idx, actionCode = "cmd_$idx", brief = "brief_$idx")
        }
    }

    private fun totalRows(worldId: WorldId, generalId: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE world_id = :w AND general_id = :g",
            MapSqlParameterSource().addValue("w", worldId.value).addValue("g", generalId),
            Int::class.java,
        ) ?: 0

    private fun outOfRangeRows(worldId: WorldId, generalId: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE world_id = :w AND general_id = :g AND (turn_idx < 0 OR turn_idx >= :max_turn)",
            MapSqlParameterSource()
                .addValue("w", worldId.value)
                .addValue("g", generalId)
                .addValue("max_turn", ReservedTurnRepository.MAX_GENERAL_TURNS),
            Int::class.java,
        ) ?: 0
}
