package opensamguk.infra.read

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
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VotePollAndDiplomacyLetterWorldScopeIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var worldOneVotes: VotePollRepository
    private lateinit var worldTwoVotes: VotePollRepository
    private lateinit var worldOneLetters: DiplomacyLetterRepository
    private lateinit var worldTwoLetters: DiplomacyLetterRepository

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        )
        worldOneVotes = VotePollRepository(jdbc, WorldId(WORLD_ONE))
        worldTwoVotes = VotePollRepository(jdbc, WorldId(WORLD_TWO))
        worldOneLetters = DiplomacyLetterRepository(jdbc, WorldId(WORLD_ONE))
        worldTwoLetters = DiplomacyLetterRepository(jdbc, WorldId(WORLD_TWO))

        insertWorld(WORLD_ONE)
        insertWorld(WORLD_TWO)
        insertVotePoll(WORLD_ONE, POLL_ID, "world-one poll", "world-one opener", 2)
        insertVotePoll(WORLD_TWO, POLL_ID, "world-two poll", "world-two opener", 1)
        insertVote(WORLD_TWO, POLL_ID, GENERAL_ID)

        insertLetter(WORLD_ONE, LETTER_ID, 101, 102, null, "PROPOSED")
        insertLetter(WORLD_TWO, LETTER_ID, 201, 202, null, "ACTIVATED")
        insertLetter(WORLD_ONE, LETTER_ID + 1, 101, 102, LETTER_ID, "CANCELLED")
        insertLetter(WORLD_TWO, LETTER_ID + 1, 201, 202, LETTER_ID, "PROPOSED")
    }

    @AfterAll
    fun tearDown() {
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `vote poll and nested vote stay inside the repository world`() {
        val now = Instant.parse("2026-07-30T00:00:00Z")

        val worldOne = assertNotNull(worldOneVotes.findPollState(POLL_ID, GENERAL_ID, now))
        val worldTwo = assertNotNull(worldTwoVotes.findPollState(POLL_ID, GENERAL_ID, now))

        assertEquals("world-one opener", worldOne.opener)
        assertEquals(2, worldOne.optionsCount)
        assertFalse(worldOne.alreadyVoted)
        assertEquals("world-two opener", worldTwo.opener)
        assertEquals(1, worldTwo.optionsCount)
        assertTrue(worldTwo.alreadyVoted)
        assertNull(VotePollRepository(jdbc, WorldId(3)).findPollState(POLL_ID, GENERAL_ID, now))
    }

    @Test
    fun `diplomacy letter id and prev chain stay inside the repository world`() {
        val worldOne = assertNotNull(worldOneLetters.findLetter(LETTER_ID))
        val worldTwo = assertNotNull(worldTwoLetters.findLetter(LETTER_ID))

        assertEquals(101, worldOne.srcNationId)
        assertEquals(102, worldOne.destNationId)
        assertEquals("proposed", worldOne.state)
        assertEquals(201, worldTwo.srcNationId)
        assertEquals(202, worldTwo.destNationId)
        assertEquals("activated", worldTwo.state)
        assertEquals(0, worldOneLetters.countNewerLetters(LETTER_ID))
        assertEquals(1, worldTwoLetters.countNewerLetters(LETTER_ID))
        assertNull(DiplomacyLetterRepository(jdbc, WorldId(3)).findLetter(LETTER_ID))
        assertEquals(0, DiplomacyLetterRepository(jdbc, WorldId(3)).countNewerLetters(LETTER_ID))
    }

    private fun insertWorld(worldId: Int) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (:world_id, :scenario_code, 200, 1, 3600)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("scenario_code", "fixture-$worldId"),
        )
    }

    private fun insertVotePoll(worldId: Int, id: Int, title: String, opener: String, optionsCount: Int) {
        val options = (1..optionsCount).joinToString(prefix = "[", postfix = "]") { "\"option-$it\"" }
        jdbc.update(
            """
            INSERT INTO vote_poll (
                world_id, id, title, options, multiple_options, reveal_mode,
                opener_general_id, opener_name
            ) VALUES (
                :world_id, :id, :title, CAST(:options AS jsonb), :multiple_options, 'always',
                10, :opener_name
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("id", id)
                .addValue("title", title)
                .addValue("options", options)
                .addValue("multiple_options", optionsCount)
                .addValue("opener_name", opener),
        )
    }

    private fun insertVote(worldId: Int, voteId: Int, generalId: Int) {
        jdbc.update(
            """
            INSERT INTO vote (world_id, vote_id, general_id, nation_id, selection)
            VALUES (:world_id, :vote_id, :general_id, 1, '[0]'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("vote_id", voteId)
                .addValue("general_id", generalId),
        )
    }

    private fun insertLetter(
        worldId: Int,
        id: Int,
        srcNationId: Int,
        destNationId: Int,
        prevId: Int?,
        state: String,
    ) {
        jdbc.update(
            """
            INSERT INTO diplomacy_letter (
                world_id, id, src_nation_id, dest_nation_id, prev_id, state,
                text_brief, text_detail, src_signer, aux
            ) VALUES (
                :world_id, :id, :src_nation_id, :dest_nation_id, :prev_id,
                CAST(:state AS diplomacy_letter_state), 'brief', 'detail', 1, '{}'::jsonb
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("id", id)
                .addValue("src_nation_id", srcNationId)
                .addValue("dest_nation_id", destNationId)
                .addValue("prev_id", prevId)
                .addValue("state", state),
        )
    }

    private companion object {
        const val WORLD_ONE = 701
        const val WORLD_TWO = 702
        const val POLL_ID = 71
        const val LETTER_ID = 81
        const val GENERAL_ID = 91
    }
}
