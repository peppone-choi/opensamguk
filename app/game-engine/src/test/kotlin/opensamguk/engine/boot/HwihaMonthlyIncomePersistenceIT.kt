package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.HwihaMonthlyCountyIncome
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * 월세입이 실제 flush 를 건너 cold load 뒤에도 정확히 한 번만 들어가는지 본다. 도장과 창고가 같은
 * flush 에 실리므로, 재기동 뒤 같은 달을 다시 돌려도 두 번 적립되지 않아야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaMonthlyIncomePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: HwihaEnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(
            NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source))
        )
        fixture = HwihaEnlistmentFixture(jdbc, flush)
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))

    @Test
    fun `monthly county income survives flush and cold load and never credits the same month twice`() {
        val id = 713
        fixture.seed(id)
        var world = cold(id)
        val county = world.administrativeCountyIds.min()
        // 소유·보급된 縣 하나에만 명시 재고를 둔다. 나머지는 창고가 없어 손대지 않는다.
        jdbc.update(
            "UPDATE city SET nation_id=1, supply_state=1, meta=?::jsonb WHERE world_id=? AND id=?",
            MetaJson.encode(
                mapOf("keep" to 17, HwihaCountyWarehouse.META_KEY to
                    HwihaCountyWarehouse(county, 0, HwihaResources(money = 5, grain = 7)).toMetaValue())
            ), id, county,
        )

        world = cold(id)
        val seeded = assertNotNull(HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county))
        assertEquals(HwihaResources(money = 5, grain = 7), seeded.stock)

        var recorder = ChangeRecorder()
        val first = assertNotNull(HwihaMonthlyCountyIncome(world, recorder).credit(200, 3))
        assertFalse(first.alreadyStamped)
        assertEquals(1, first.creditedCounties, "창고가 있는 縣 하나만 적립된다")
        save(world, recorder)

        // 재기동: 새 스냅샷에 재고와 도장이 모두 남아야 한다.
        world = cold(id)
        val reloaded = assertNotNull(HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county))
        assertEquals(seeded.stock.credit(first.total), reloaded.stock)
        assertEquals(1, reloaded.revision)
        assertEquals(17, (world.getCityById(county)!!.meta["keep"] as Number).toInt())
        assertEquals(
            HwihaMonthlyCountyIncome.stampOf(200, 3),
            world.getState().meta[HwihaMonthlyCountyIncome.STAMP_KEY],
            "도장이 창고와 같은 flush 로 저장됐다",
        )

        // 같은 달 재실행은 막힌다 — 재기동 뒤에도 이중 적립이 없다.
        recorder = ChangeRecorder()
        val retry = assertNotNull(HwihaMonthlyCountyIncome(world, recorder).credit(200, 3))
        assertTrue(retry.alreadyStamped)
        save(world, recorder)
        world = cold(id)
        assertEquals(reloaded, HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county))

        // 다음 달은 다시 들어간다.
        recorder = ChangeRecorder()
        val next = assertNotNull(HwihaMonthlyCountyIncome(world, recorder).credit(200, 4))
        assertFalse(next.alreadyStamped)
        assertEquals(first.total, next.total)
        save(world, recorder)
        world = cold(id)
        val after = assertNotNull(HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county))
        assertEquals(seeded.stock.credit(first.total).credit(next.total), after.stock)
        assertEquals(2, after.revision)
        assertEquals(
            HwihaMonthlyCountyIncome.stampOf(200, 4),
            world.getState().meta[HwihaMonthlyCountyIncome.STAMP_KEY],
        )
    }
}
