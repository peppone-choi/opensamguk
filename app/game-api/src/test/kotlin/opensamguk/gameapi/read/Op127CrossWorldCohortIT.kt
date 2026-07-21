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
 * OPENSAM-127 GWT: process world=1 never returns world=2 rows for rank / auction / log / diplomacy.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    RankDataReadRepository::class,
    AuctionCountReadRepository::class,
    DiplomacyReadRepository::class,
    LogFeedReadRepository::class,
    WorldStateReadRepository::class,
)
class Op127CrossWorldCohortIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var ranks: RankDataReadRepository
    @Autowired lateinit var auctions: AuctionCountReadRepository
    @Autowired lateinit var diplomacy: DiplomacyReadRepository
    @Autowired lateinit var logFeeds: LogFeedReadRepository
    @Autowired lateinit var worldStates: WorldStateReadRepository

    @Test
    fun `process world isolates rank auction log diplomacy and world_state`() {
        seedWorld(1)
        seedWorld(2)
        jdbc.update(
            "INSERT INTO rank_data (id, world_id, nation_id, general_id, type, value) VALUES (1, 1, 1, 10, 'warnum', 7)",
        )
        jdbc.update(
            "INSERT INTO rank_data (id, world_id, nation_id, general_id, type, value) VALUES (2, 2, 1, 10, 'warnum', 99)",
        )
        jdbc.update(
            """
            INSERT INTO ng_auction (id, world_id, type, finished, host_general_id, req_resource, open_date, close_date)
            VALUES (1, 1, 'buyRice', false, 1, 'gold', now(), now() + interval '1 hour'),
                   (2, 2, 'buyRice', false, 1, 'gold', now(), now() + interval '1 hour')
            """.trimIndent(),
        )
        jdbc.update(
            "INSERT INTO diplomacy (id, world_id, src_nation_id, dest_nation_id, state_code, term) VALUES (1, 1, 5, 6, 1, 0), (2, 2, 5, 6, 2, 0)",
        )
        jdbc.update(
            """
            INSERT INTO log_entry (world_id, id, scope, category, year, month, text)
            VALUES (1, 1, 'SYSTEM', 'HISTORY', 200, 1, 'w1'),
                   (2, 2, 'SYSTEM', 'HISTORY', 200, 1, 'w2')
            """.trimIndent(),
        )

        assertEquals(listOf(7), ranks.findByGeneralId(10).map { it.value })
        assertEquals(7, ranks.findByGeneralIdAndType(10, "warnum")!!.value)
        assertEquals(1, auctions.countByFinished(false))
        assertEquals(listOf(1), diplomacy.findBySrcNationId(5).map { it.stateCode })
        assertEquals(listOf("w1"), logFeeds.findRecentGlobalHistory(10).map { it.text })
        assertEquals(listOf(1), worldStates.findAll().map { it.id })
        assertTrue(worldStates.findById(2).isEmpty)
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, ?, 1, 1, 60)",
            id,
            "world-$id",
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
