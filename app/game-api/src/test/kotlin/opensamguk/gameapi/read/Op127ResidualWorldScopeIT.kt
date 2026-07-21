package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-127 residual GWT: history/troop/hall/vote/board/access/log cohorts stay process-world scoped.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    HistoryReadRepository::class,
    TroopReadRepository::class,
    HallReadRepository::class,
    VotePollReadRepository::class,
    BoardPostReadRepository::class,
    GeneralAccessLogReadRepository::class,
    WorldLogReadRepository::class,
    NationLogReadRepository::class,
)
class Op127ResidualWorldScopeIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var history: HistoryReadRepository
    @Autowired lateinit var troops: TroopReadRepository
    @Autowired lateinit var halls: HallReadRepository
    @Autowired lateinit var polls: VotePollReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var access: GeneralAccessLogReadRepository
    @Autowired lateinit var worldLogs: WorldLogReadRepository
    @Autowired lateinit var nationLogs: NationLogReadRepository

    @Test
    fun `process world isolates residual world-owned cohorts`() {
        seedWorld(1)
        seedWorld(2)
        jdbc.update(
            "INSERT INTO yearbook_history (id, world_id, server_id, profile_name, year, month, map, nations, hash) " +
                "VALUES (1, 1, 's1', 'p', 200, 1, '{}'::jsonb, '{}'::jsonb, 'h1'), " +
                "(2, 2, 's2', 'p', 200, 1, '{}'::jsonb, '{}'::jsonb, 'h2')",
        )
        jdbc.update(
            "INSERT INTO troop (world_id, troop_leader, nation, name) VALUES (1, 10, 1, 'tA'), (2, 10, 1, 'tB')",
        )
        jdbc.update(
            "INSERT INTO hall (id, world_id, server_id, season, scenario, general_no, type, value, owner, aux) " +
                "VALUES (1, 1, 's', 1, 1, 1, 'war', 1.0, null, '{}'::jsonb), " +
                "(2, 2, 's', 1, 1, 1, 'war', 9.0, null, '{}'::jsonb)",
        )
        jdbc.update(
            "INSERT INTO vote_poll (id, world_id, title, body, options, multiple_options, reveal_mode, opener_general_id, opener_name, start_at) " +
                "VALUES (1, 1, 'a', 'b', '{}'::jsonb, 1, 'open', 1, 'g', now()), " +
                "(2, 2, 'x', 'y', '{}'::jsonb, 1, 'open', 1, 'g', now())",
        )
        jdbc.update(
            "INSERT INTO board_post (id, world_id, nation_id, is_secret, author_general_id, author_name, title, content_html, created_at) " +
                "VALUES (1, 1, 1, false, 1, 'a', 't1', 'c', now()), (2, 2, 1, false, 1, 'a', 't2', 'c', now())",
        )
        jdbc.update(
            "INSERT INTO general_access_log (id, world_id, general_id, refresh) VALUES (1, 1, 5, 3), (2, 2, 5, 9)",
        )
        jdbc.update(
            "INSERT INTO log_entry (world_id, id, scope, category, year, month, text) " +
                "VALUES (1, 11, 'SYSTEM', 'HISTORY', 200, 1, 'w1'), (2, 12, 'SYSTEM', 'HISTORY', 200, 1, 'w2')",
        )
        jdbc.update(
            "INSERT INTO log_entry (world_id, id, scope, category, year, month, text, nation_id) " +
                "VALUES (1, 21, 'NATION', 'HISTORY', 200, 1, 'n1', 3), (2, 22, 'NATION', 'HISTORY', 200, 1, 'n2', 3)",
        )

        assertEquals(listOf("h1"), history.findAllByOrderByYearAscMonthAsc().map { it.hash })
        assertEquals(listOf("tA"), troops.findByNationOrderByTroopLeaderAsc(1).map { it.name })
        assertEquals(listOf(1.0), halls.findAllByOrderByTypeAscValueDescIdAsc().map { it.value })
        assertEquals(listOf("a"), polls.findAllByOrderByIdDesc().map { it.title })
        assertEquals(listOf("t1"), posts.findByIsSecretOrderByCreatedAtDescIdDesc(false).map { it.title })
        assertEquals(3, access.findByGeneralId(5)!!.refresh)
        assertEquals(listOf("w1"), worldLogs.findRecentWorldLog(10).map { it.text })
        assertEquals(listOf("n1"), nationLogs.findAllNationHistory(3).map { it.text })
        assertTrue(history.findByYearAndMonthOrderByIdAsc(200, 1).none { it.hash == "h2" })
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, ?, 1, 1, 60)",
            id,
            "w$id",
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("opensamguk.world-id") { 1 }
        }
    }
}
