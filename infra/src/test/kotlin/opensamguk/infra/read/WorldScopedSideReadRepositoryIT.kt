package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.worldstate.WorldStateRepository
import opensamguk.logic.auction.AuctionType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SideReadRepositoryConfiguration::class, WorldOneScopeConfiguration::class)
class WorldScopedSideReadRepositoryIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var auctions: AuctionRepository
    @Autowired lateinit var bids: AuctionBidRepository
    @Autowired lateinit var betting: BettingRepository
    @Autowired lateinit var boardPosts: BoardPostRepository
    @Autowired lateinit var gameKv: GameKvRepository
    @Autowired lateinit var inheritance: InheritanceRepository
    @Autowired lateinit var diplomacy: DiplomacyRepository
    @Autowired lateinit var worldState: WorldStateRepository
    @Autowired lateinit var selectPool: SelectPoolRepository

    @BeforeEach
    fun seedTwoWorlds() {
        listOf(1, 2).forEach { worldId ->
            jdbc.update(
                """
                INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
                VALUES (?, ?, 200, 1, 60)
                """.trimIndent(),
                worldId,
                "world-$worldId",
            )
            insertNation(worldId, 10)
            insertNation(worldId, 11)
            updateSelectPoolConfig(worldId)
        }

        insertAuction(worldId = 1, id = 7, target = "world-one")
        insertAuction(worldId = 2, id = 7, target = "world-two")
        insertAuction(worldId = 2, id = 99, target = "world-two-max")
        insertBid(worldId = 1, no = 1, auctionId = 7, amount = 100)
        insertBid(worldId = 2, no = 1, auctionId = 7, amount = 999)
        insertBet(worldId = 1, id = 1, amount = 120)
        insertBet(worldId = 2, id = 1, amount = 900)
        insertBoardPost(worldId = 1, id = 5, title = "world-one")
        insertBoardPost(worldId = 2, id = 5, title = "world-two")
        insertKv(worldId = 1, id = 10, table = "betting", value = "\"world-one\"")
        insertKv(worldId = 2, id = 11, table = "betting", value = "\"world-two\"")
        insertKv(worldId = null, id = 20, table = "inheritance", value = "[77]")
        insertDiplomacy(worldId = 1, id = 3, stateCode = 1)
        insertDiplomacy(worldId = 2, id = 3, stateCode = 5)
        insertSelectPool(worldId = 1, id = 7, uniqueName = "shared-name", owner = 101, generalId = 42)
        insertSelectPool(worldId = 2, id = 7, uniqueName = "shared-name", owner = 202, generalId = 42)
        insertSelectPool(worldId = 2, id = 8, uniqueName = "world-two-only", owner = 202, generalId = null)
    }

    @Test
    fun `identical local ids stay inside the process world across side reads`() {
        assertEquals("world-one", auctions.findById(7).orElseThrow().target)
        assertEquals(listOf(7), auctions.findByFinishedFalse().mapNotNull { it.id })
        assertEquals(7, auctions.findMaxId())

        assertEquals(100, bids.findTopByAuctionIdOrderByAmountDesc(7)?.amount)
        assertEquals(
            listOf(100),
            bids.findHighestBidsByAuctionIds(listOf(7)).map { it.amount },
        )

        assertEquals(120L, betting.aggregateTotalAmountByBetting().single().sumAmount)
        assertEquals(120L, betting.aggregateAmountByType(3).single().sumAmount)
        assertEquals(120L, betting.sumAmountByBettingIdAndUserId(3, 100))
        assertEquals("world-one", boardPosts.findByIdAndNationId(5, 10)?.title)
        assertEquals(listOf("\"world-one\""), gameKv.findByTable("betting").map { it.value })
        assertEquals("[77]", inheritance.findByInheritanceNamespace("inheritance_100").single().value)
        assertEquals(1, diplomacy.findBySrcNationIdAndDestNationId(10, 11)?.stateCode)
        assertEquals(1, worldState.findProcessWorld()?.id)
        assertTrue(worldState.findById(2).isEmpty)

        val now = Instant.now()
        assertEquals(listOf("shared-name"), selectPool.listForUser(101, now).map { it.uniqueName })
        assertTrue(selectPool.listForUser(202, now).isEmpty())
        assertEquals(null, selectPool.findPoolEntry("shared-name", 202, now))
        assertEquals(setOf("shared-name"), selectPool.listUniqueNames())
        assertEquals("WorldOnePool", selectPool.targetGeneralPool())
        assertEquals(setOf("stat"), selectPool.allowedCustomOptions())
        assertEquals(1, selectPool.showImageLevel())

        val worldTwoSelectPool = SelectPoolRepository(NamedParameterJdbcTemplate(jdbc), WorldId(2))
        val worldTwoEntry = assertNotNull(worldTwoSelectPool.findPoolEntry("shared-name", 202, now))
        assertEquals(202, worldTwoEntry.ownerUserId)
        assertEquals(listOf("shared-name", "world-two-only"), worldTwoSelectPool.listForUser(202, now).map { it.uniqueName })
        assertEquals(setOf("shared-name", "world-two-only"), worldTwoSelectPool.listUniqueNames())
        assertEquals("WorldTwoPool", worldTwoSelectPool.targetGeneralPool())
        assertEquals(setOf("picture"), worldTwoSelectPool.allowedCustomOptions())
        assertEquals(9, worldTwoSelectPool.showImageLevel())
    }

    private fun insertNation(worldId: Int, id: Int) {
        jdbc.update(
            "INSERT INTO nation (world_id, id, name, color) VALUES (?, ?, ?, '#000000')",
            worldId,
            id,
            "nation-$worldId-$id",
        )
    }

    private fun updateSelectPoolConfig(worldId: Int) {
        val config = if (worldId == 1) {
            """{"map":{"generalPoolAllowOption":["stat"],"targetGeneralPool":"WorldOnePool"},"show_img_level":1}"""
        } else {
            """{"map":{"generalPoolAllowOption":["picture"],"targetGeneralPool":"WorldTwoPool"},"show_img_level":9}"""
        }
        jdbc.update("UPDATE world_state SET config = ?::jsonb WHERE id = ?", config, worldId)
    }

    private fun insertSelectPool(worldId: Int, id: Int, uniqueName: String, owner: Int, generalId: Int?) {
        jdbc.update(
            """
            INSERT INTO select_pool (world_id, id, unique_name, owner, general_id, reserved_until, info)
            VALUES (?, ?, ?, ?, ?, now() + interval '1 hour', '{}')
            """.trimIndent(),
            worldId,
            id,
            uniqueName,
            owner,
            generalId,
        )
    }
    private fun insertAuction(worldId: Int, id: Int, target: String) {
        jdbc.update(
            """
            INSERT INTO ng_auction (
                world_id, id, type, finished, target, host_general_id, req_resource,
                open_date, close_date, detail
            ) VALUES (?, ?, 'uniqueItem', false, ?, 0, 'gold', now(), now() + interval '1 day', '{}')
            """.trimIndent(),
            worldId,
            id,
            target,
        )
    }

    private fun insertBid(worldId: Int, no: Int, auctionId: Int, amount: Int) {
        jdbc.update(
            """
            INSERT INTO ng_auction_bid (world_id, no, auction_id, owner, general_id, amount, date, aux)
            VALUES (?, ?, ?, 100, 10, ?, now(), '{}')
            """.trimIndent(),
            worldId,
            no,
            auctionId,
            amount,
        )
    }

    private fun insertBet(worldId: Int, id: Int, amount: Int) {
        jdbc.update(
            """
            INSERT INTO ng_betting (world_id, id, betting_id, general_id, user_id, betting_type, amount)
            VALUES (?, ?, 3, 10, 100, '[0]', ?)
            """.trimIndent(),
            worldId,
            id,
            amount,
        )
    }

    private fun insertBoardPost(worldId: Int, id: Int, title: String) {
        jdbc.update(
            """
            INSERT INTO board_post (
                world_id, id, nation_id, is_secret, author_general_id, author_name,
                title, content_html
            ) VALUES (?, ?, 10, false, 10, 'author', ?, 'content')
            """.trimIndent(),
            worldId,
            id,
            title,
        )
    }

    private fun insertKv(worldId: Int?, id: Int, table: String, value: String) {
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, id, "table", namespace, key, value)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            worldId,
            id,
            table,
            if (table == "inheritance") "inheritance_100" else "betting",
            if (table == "inheritance") "previous" else "id_3",
            value,
        )
    }

    private fun insertDiplomacy(worldId: Int, id: Int, stateCode: Int) {
        jdbc.update(
            """
            INSERT INTO diplomacy (
                world_id, id, src_nation_id, dest_nation_id, state_code, term,
                is_dead, is_showing, meta
            ) VALUES (?, ?, 10, 11, ?, 0, false, true, '{}')
            """.trimIndent(),
            worldId,
            id,
            stateCode,
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
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class WorldOneScopeConfiguration {
    @Bean
    fun sideReadWorldScope(): SideReadWorldScope = SideReadWorldScope(WorldId(1))
}
