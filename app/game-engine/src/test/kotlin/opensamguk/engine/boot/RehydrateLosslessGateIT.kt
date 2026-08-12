package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.auction.AuctionInfo
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RehydrateLosslessGateIT {

    private val worldId = WorldId(401)
    private val secondWorldId = WorldId(402)
    private val thirdWorldId = WorldId(403)
    private val hostGeneralId = 701
    private val auctionId = 901
    private val isolationAuctionId = 902
    private val uniqueItem = "che_명마_07_백마"
    private val firstWorldIsolationItem = "che_명마_08_흑왕"
    private val secondWorldIsolationItem = "che_명마_09_적토마"

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
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
        jdbc = JdbcTemplate(dataSource)
        executor = JdbcFlushExecutor(
            NamedParameterJdbcTemplate(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
        )

        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (?, 'scenario_0', 200, 1, 3600)
            """.trimIndent(),
            worldId.value,
        )
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (?, 'scenario_0', 200, 1, 3600)
            """.trimIndent(),
            secondWorldId.value,
        )
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (?, 'scenario_0', 200, 1, 3600)
            """.trimIndent(),
            thirdWorldId.value,
        )
        jdbc.update(
            """
            INSERT INTO general (id, world_id, name, nation_id, turn_time)
            VALUES (?, ?, '경매주최장수', 0, now())
            """.trimIndent(),
            hostGeneralId,
            worldId.value,
        )
        jdbc.update(
            """
            INSERT INTO general (id, world_id, name, nation_id, turn_time)
            VALUES (?, ?, '격리세계장수', 0, now())
            """.trimIndent(),
            hostGeneralId,
            secondWorldId.value,
        )
        jdbc.update(
            """
            INSERT INTO general (id, world_id, name, nation_id, turn_time)
            VALUES (?, ?, '격리세계장수둘', 0, now())
            """.trimIndent(),
            hostGeneralId,
            thirdWorldId.value,
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `flushed unique auction keeps the resident projection equal to the rehydrated projection`() {
        val loader = loader(worldId)
        val world = InMemoryTurnWorld(loader.buildSnapshot())
        val recorder = ChangeRecorder(auctionIdAllocator = { auctionId })
        val auction = uniqueAuction(uniqueItem, "백마 경매", "경매주최장수")

        assertEquals(auctionId, recorder.recordAuctionUpsert(id = null, columns = auction.toArray()))
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        executor.flush(payload)
        recorder.clear()

        assertEquals(
            1,
            executor.lastOps().count { it.table == "ng_auction" },
            "the recorder intent must use the JDBC auction flush seam exactly once",
        )
        assertEquals(
            uniqueItem,
            jdbc.queryForObject(
                "SELECT target FROM ng_auction WHERE world_id = ? AND id = ?",
                String::class.java,
                worldId.value,
                auctionId,
            ),
            "precondition: the recorder auction upsert reaches PostgreSQL",
        )
        val rehydratedItems = loader.buildSnapshot().state.meta["activeUniqueAuctionItems"]
        assertEquals(listOf(uniqueItem), rehydratedItems, "precondition: the loader reconstructs the durable auction")
        assertEquals(
            rehydratedItems,
            world.getState().meta["activeUniqueAuctionItems"],
            "a restart sees the unique auction but the uninterrupted N+1 world must see the same eligibility projection",
        )

        recorder.recordAuctionUpsert(
            id = auctionId,
            columns = auction.copy(finished = true).toArray(withoutId = true),
        )
        executor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        recorder.clear()

        assertEquals(
            1,
            executor.lastOps().count { it.table == "ng_auction" },
            "the finalization must use the JDBC auction update seam exactly once",
        )
        assertEquals(
            true,
            jdbc.queryForObject(
                "SELECT finished FROM ng_auction WHERE world_id = ? AND id = ?",
                Boolean::class.java,
                worldId.value,
                auctionId,
            ),
            "precondition: the recorder auction update reaches PostgreSQL",
        )
        val rehydratedAfterFinish = loader.buildSnapshot().state.meta["activeUniqueAuctionItems"]
        assertEquals(emptyList<String>(), rehydratedAfterFinish)
        assertEquals(
            rehydratedAfterFinish,
            world.getState().meta["activeUniqueAuctionItems"],
            "the active projection must remove a finalized auction without requiring a restart",
        )
    }

    @Test
    fun `same local auction id remains isolated through flush and reload`() {
        val firstLoader = loader(secondWorldId)
        val secondLoader = loader(thirdWorldId)
        val firstWorld = InMemoryTurnWorld(firstLoader.buildSnapshot())
        val secondWorld = InMemoryTurnWorld(secondLoader.buildSnapshot())
        val firstRecorder = ChangeRecorder(auctionIdAllocator = { isolationAuctionId })
        val secondRecorder = ChangeRecorder(auctionIdAllocator = { isolationAuctionId })

        firstRecorder.recordAuctionUpsert(
            id = null,
            columns = uniqueAuction(firstWorldIsolationItem, "첫 세계 경매", "경매주최장수").toArray(),
        )
        secondRecorder.recordAuctionUpsert(
            id = null,
            columns = uniqueAuction(secondWorldIsolationItem, "둘째 세계 경매", "격리세계장수").toArray(),
        )
        executor.flush(DatabaseHooks.toFlushPayload(firstWorld, firstRecorder, firstWorld.consumeDirtyState()))
        executor.flush(DatabaseHooks.toFlushPayload(secondWorld, secondRecorder, secondWorld.consumeDirtyState()))

        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM ng_auction WHERE id = ?",
                Int::class.java,
                isolationAuctionId,
            ),
            "two world-scoped rows may share the same local auction id",
        )
        val firstItems = firstLoader.buildSnapshot().state.meta["activeUniqueAuctionItems"] as List<*>
        val secondItems = secondLoader.buildSnapshot().state.meta["activeUniqueAuctionItems"] as List<*>
        assertEquals(
            listOf(firstWorldIsolationItem),
            firstItems.filter { it == firstWorldIsolationItem || it == secondWorldIsolationItem },
            "world ${secondWorldId.value} must not rehydrate the same local id from world ${thirdWorldId.value}",
        )
        assertEquals(
            listOf(secondWorldIsolationItem),
            secondItems.filter { it == firstWorldIsolationItem || it == secondWorldIsolationItem },
            "world ${thirdWorldId.value} must not rehydrate the same local id from world ${secondWorldId.value}",
        )
        assertFalse(firstItems.contains(secondWorldIsolationItem))
        assertFalse(secondItems.contains(firstWorldIsolationItem))
    }

    private fun loader(worldId: WorldId): WorldSnapshotLoader = WorldSnapshotLoader(
        jdbc = jdbc,
        seedBootstrap = SeedBootstrap(
            scenarioCode = "scenario_0",
            seedEnabled = false,
            worldId = worldId,
        ),
        worldId = worldId,
    )

    private fun uniqueAuction(target: String, title: String, hostName: String): AuctionInfo = AuctionInfo(
        id = null,
        type = AuctionType.UNIQUE_ITEM,
        finished = false,
        target = target,
        hostGeneralId = hostGeneralId,
        reqResource = ResourceType.INHERITANCE_POINT,
        openDate = "0200-01-01T00:00:00Z",
        closeDate = "0200-01-02T00:00:00Z",
        detail = AuctionInfoDetail(
            title = title,
            hostName = hostName,
            amount = 1,
            startBidAmount = 1000,
        ),
    )
}
