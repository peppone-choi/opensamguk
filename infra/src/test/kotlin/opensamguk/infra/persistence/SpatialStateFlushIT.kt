package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.ProvinceControlState
import opensamguk.logic.world.StrategicNodeRef
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpatialStateFlushIT {
    private val hash = "a".repeat(64)
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
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
        jdbc = NamedParameterJdbcTemplate(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
    }

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM general", MapSqlParameterSource())
        jdbc.update("DELETE FROM world_state", MapSqlParameterSource())
        for (worldId in 1..2) {
            jdbc.update(
                """
                INSERT INTO world_state
                    (id, scenario_code, current_year, current_month, tick_seconds, world_version, writer_epoch)
                VALUES (:id, :scenario, 200, 1, 60, 5, 9)
                """.trimIndent(),
                MapSqlParameterSource().addValue("id", worldId).addValue("scenario", "spatial-$worldId"),
            )
            jdbc.update(
                "INSERT INTO general (world_id, id, name, turn_time) VALUES (:world_id, 10, :name, now())",
                MapSqlParameterSource().addValue("world_id", worldId).addValue("name", "g$worldId"),
            )
        }
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `insert then coalesced update persists both typed state channels`() {
        executor.flush(
            payload(
                worldId = 1,
                province = ProvinceControlWriteRow(null, province("p1", 3, 1)),
                position = GeneralPositionWriteRow(null, position(StrategicNodeRef.LandProvince("p1"), 1)),
            ),
        )
        executor.flush(
            payload(
                worldId = 1,
                province = ProvinceControlWriteRow(1, province("p1", 7, 4)),
                position = GeneralPositionWriteRow(1, position(StrategicNodeRef.WaterZone("w1"), 3)),
            ),
        )

        val provinceRow = provinceRow(1, "p1")
        assertEquals(7, provinceRow["nation_id"])
        assertEquals(4L, provinceRow["revision"])
        val positionRow = positionRow(1, 10)
        assertEquals("WATER_ZONE", positionRow["node_kind"])
        assertEquals("w1", positionRow["node_id"])
        assertEquals(3L, positionRow["revision"])
    }

    @Test
    fun `same local IDs remain isolated between worlds`() {
        executor.flush(
            payload(
                worldId = 1,
                province = ProvinceControlWriteRow(null, province("same", 1, 1)),
                position = GeneralPositionWriteRow(null, position(StrategicNodeRef.LandProvince("west"), 1)),
            ),
        )
        executor.flush(
            payload(
                worldId = 2,
                province = ProvinceControlWriteRow(null, province("same", 2, 1)),
                position = GeneralPositionWriteRow(null, position(StrategicNodeRef.WaterZone("east"), 1)),
            ),
        )

        assertEquals(1, provinceRow(1, "same")["nation_id"])
        assertEquals(2, provinceRow(2, "same")["nation_id"])
        assertEquals("west", positionRow(1, 10)["node_id"])
        assertEquals("east", positionRow(2, 10)["node_id"])
    }

    @Test
    fun `stale position rolls back province write and world fence atomically`() {
        executor.flush(
            payload(
                worldId = 1,
                province = ProvinceControlWriteRow(null, province("p1", 3, 1)),
                position = GeneralPositionWriteRow(null, position(StrategicNodeRef.LandProvince("old"), 1)),
            ),
        )

        assertFailsWith<StaleGeneralPositionException> {
            executor.flush(
                payload(
                    worldId = 1,
                    province = ProvinceControlWriteRow(1, province("p1", 9, 2)),
                    position = GeneralPositionWriteRow(2, position(StrategicNodeRef.WaterZone("new"), 3)),
                    expectedWorldVersion = 5,
                    currentYear = 201,
                ),
            )
        }

        val provinceRow = provinceRow(1, "p1")
        assertEquals(3, provinceRow["nation_id"])
        assertEquals(1L, provinceRow["revision"])
        val positionRow = positionRow(1, 10)
        assertEquals("LAND_PROVINCE", positionRow["node_kind"])
        assertEquals("old", positionRow["node_id"])
        assertEquals(1L, positionRow["revision"])
        val worldRow = jdbc.queryForMap(
            "SELECT current_year, world_version FROM world_state WHERE id = 1",
            emptyMap<String, Any>(),
        )
        assertEquals(200, worldRow["current_year"])
        assertEquals(5L, worldRow["world_version"])
    }

    @Test
    fun `general deletion cascades position only in the payload world`() {
        executor.flush(payload(1, position = GeneralPositionWriteRow(null, position(StrategicNodeRef.LandProvince("p1"), 1))))
        executor.flush(payload(2, position = GeneralPositionWriteRow(null, position(StrategicNodeRef.LandProvince("p2"), 1))))

        executor.flush(
            FlushPayload(
                worldId = WorldId(1),
                worldStateUpdate = worldState(1),
                deletedGenerals = listOf(10),
            ),
        )

        assertEquals(0, count("general_spatial_position", 1))
        assertEquals(1, count("general_spatial_position", 2))
        assertEquals(0, count("general", 1))
        assertEquals(1, count("general", 2))
    }

    private fun payload(
        worldId: Int,
        province: ProvinceControlWriteRow? = null,
        position: GeneralPositionWriteRow? = null,
        expectedWorldVersion: Long? = null,
        currentYear: Int = 200,
    ) = FlushPayload(
        worldId = WorldId(worldId),
        worldStateUpdate = worldState(worldId, expectedWorldVersion, currentYear),
        provinceControlWrites = ProvinceControlWriteBatch(listOfNotNull(province)),
        generalPositionWrites = GeneralPositionWriteBatch(listOfNotNull(position)),
    )

    private fun worldState(worldId: Int, expectedWorldVersion: Long? = null, currentYear: Int = 200) =
        linkedMapOf<String, Any?>(
            "id" to worldId,
            "current_year" to currentYear,
            "current_month" to 1,
        ).apply {
            if (expectedWorldVersion != null) {
                put("expected_world_version", expectedWorldVersion)
                put("writer_epoch", 9L)
            }
        }

    private fun province(id: String, nationId: Int, revision: Long) =
        ProvinceControlState("r1", hash, id, nationId, revision)

    private fun position(node: StrategicNodeRef, revision: Long) =
        GeneralPositionState("r1", hash, 10, node, revision)

    private fun provinceRow(worldId: Int, provinceId: String): Map<String, Any> = jdbc.queryForMap(
        "SELECT nation_id, revision FROM province_control WHERE world_id = :world_id AND province_id = :province_id",
        mapOf("world_id" to worldId, "province_id" to provinceId),
    )

    private fun positionRow(worldId: Int, generalId: Int): Map<String, Any> = jdbc.queryForMap(
        "SELECT node_kind, node_id, revision FROM general_spatial_position WHERE world_id = :world_id AND general_id = :general_id",
        mapOf("world_id" to worldId, "general_id" to generalId),
    )

    private fun count(table: String, worldId: Int): Int = jdbc.queryForObject(
        "SELECT count(*) FROM $table WHERE world_id = :world_id",
        mapOf("world_id" to worldId),
        Int::class.java,
    ) ?: -1
}
