package opensamguk.engine.v2

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.FlushVerb
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.CityLedgerV2UpsertRow
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-150 (R1) — v2 도시 원장 쓰기 경로의 실DB 증명:
 * [V2CityLedgerStore] → [ChangeRecorder] → `FlushPayload` → [JdbcFlushExecutor]의 v2 step.
 *
 * **왜 infra가 아니라 engine 테스트인가.** DoD는 "infra flush IT"라고 적었으나 이 체인의 앞 두 마디
 * ([ChangeRecorder]·[V2CityLedgerStore])는 `:app:game-engine` 소속이고 `:infra`는 엔진에 의존하지
 * 않는다. 엔진 쪽에 두면 executor를 그대로 쓰면서 채널 전체를 한 번에 증명할 수 있다 — 반대 방향은
 * 불가능하다. 증명 대상(멱등 UPSERT · v1 델타와 같은 트랜잭션)은 동일하다.
 *
 * Flyway location은 v2 스택 운영값과 같은 sibling 쌍이다(`db/migration_v2/README.md` §2 — 오버라이드는
 * 치환이라 v1 location을 반드시 함께 넣는다).
 *
 * Docker 미가용 시 skip — fail이 아니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V2CityLedgerFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private val worldId = WorldId(1)

    private fun worldState(year: Int) =
        linkedMapOf<String, Any?>("id" to 1, "current_year" to year, "current_month" to 1)

    private fun recorder() = ChangeRecorder()

    @BeforeAll
    fun setUp() {
        Assumptions.assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration", "classpath:db/migration_v2")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) " +
                "VALUES (1, 'sc', 181, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun flush(year: Int, recorder: ChangeRecorder) = executor.flush(
        FlushPayload(
            worldId = worldId,
            worldStateUpdate = worldState(year),
            cityLedgerV2Upserts = recorder.cityLedgerV2Upserts().map { CityLedgerV2UpsertRow(it.columns) },
        ),
    )

    private fun ledgerRow(cityId: Int): Map<String, Any?>? = jdbc.jdbcTemplate
        .queryForList("SELECT * FROM v2_city_ledger WHERE world_id = 1 AND city_id = $cityId")
        .firstOrNull()

    private fun currentYear(): Int = jdbc.jdbcTemplate
        .queryForObject("SELECT current_year FROM world_state WHERE id = 1", Int::class.java)!!

    @Test
    fun `store adjust 델타가 recorder를 거쳐 v2_city_ledger에 절대값으로 영속된다`() {
        val store = V2CityLedgerStore(jdbc)
        val recorder = recorder()

        val after = store.adjust(worldId, recorder, cityId = 5, goldDelta = 1200, riceDelta = 800, garrisonDelta = 300)
        assertEquals(1200L, after.gold)
        assertEquals(300, after.garrison)

        flush(182, recorder)

        val row = ledgerRow(5)!!
        assertEquals(1200L, row["gold"])
        assertEquals(800L, row["rice"])
        assertEquals(300, row["garrison"])
        assertEquals(FlushVerb.UPSERT, executor.lastOps().single { it.table == "v2_city_ledger" }.verb)
        // v1 델타(world_state)와 v2 델타가 같은 flush 호출에서 함께 반영됐다.
        assertEquals(182, currentYear())
    }

    @Test
    fun `같은 payload 재적용은 멱등이다 -- 누적이 아니라 덮어쓰기`() {
        val store = V2CityLedgerStore(jdbc)
        val recorder = recorder()
        store.adjust(worldId, recorder, cityId = 6, goldDelta = 500, riceDelta = 100, garrisonDelta = 40)
        val rows = recorder.cityLedgerV2Upserts().map { CityLedgerV2UpsertRow(it.columns) }

        val payload = FlushPayload(worldId, worldState(183), cityLedgerV2Upserts = rows)
        executor.flush(payload)
        executor.flush(payload) // 재시작 후 같은 델타 재적용 시뮬레이션

        val row = ledgerRow(6)!!
        assertEquals(500L, row["gold"], "절대값 UPSERT — 두 번 적용해도 1000이 되지 않는다")
        assertEquals(40, row["garrison"])
        assertEquals(
            1,
            jdbc.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM v2_city_ledger WHERE world_id = 1 AND city_id = 6", Int::class.java,
            ),
            "PK (world_id, city_id) — 행 중복 없음",
        )
    }

    @Test
    fun `store는 lazy 적재라 부팅 순서에 의존하지 않는다 -- 첫 접근이 기존 행을 읽는다`() {
        jdbc.update(
            "INSERT INTO v2_city_ledger (world_id, city_id, gold, rice, garrison) VALUES (1, 7, 9, 8, 7)",
            MapSqlParameterSource(),
        )
        // 행이 이미 있는 상태에서 처음 생성된 store: 생성 시점이 아니라 첫 접근에 적재한다.
        val store = V2CityLedgerStore(jdbc)
        assertEquals(V2CityLedgerEntry(9, 8, 7), store.entry(worldId, 7))

        val recorder = recorder()
        store.adjust(worldId, recorder, cityId = 7, goldDelta = 1)
        flush(184, recorder)
        assertEquals(10L, ledgerRow(7)!!["gold"], "적재된 기존 값 위에 델타가 얹힌다")
    }

    @Test
    fun `원장은 음수로 내려가지 않는다`() {
        val store = V2CityLedgerStore(jdbc)
        val recorder = recorder()
        val after = store.adjust(worldId, recorder, cityId = 8, goldDelta = -50, riceDelta = -1, garrisonDelta = -9)
        assertEquals(V2CityLedgerEntry(0, 0, 0), after)
        flush(185, recorder)
        assertEquals(0L, ledgerRow(8)!!["gold"])
    }

    @Test
    fun `v2 step 실패는 같은 트랜잭션의 v1 델타까지 롤백한다`() {
        val yearBefore = currentYear()
        val brokenYear = yearBefore + 7
        assertNotEquals(yearBefore, brokenYear)

        val broken = FlushPayload(
            worldId = worldId,
            worldStateUpdate = worldState(brokenYear),
            // city_id NULL → NOT NULL 위반. v1 world_state UPDATE는 이미 실행된 뒤다(step 1 vs step 14).
            cityLedgerV2Upserts = listOf(
                CityLedgerV2UpsertRow(linkedMapOf("city_id" to null, "gold" to 1L, "rice" to 1L, "garrison" to 1)),
            ),
        )
        val error = runCatching { executor.flush(broken) }.exceptionOrNull()
        assertTrue(error != null, "v2 step의 제약 위반은 flush를 실패시켜야 한다")
        assertEquals(
            yearBefore, currentYear(),
            "v1 델타와 v2 델타는 같은 TransactionTemplate에서 커밋된다 — v2 실패 시 v1도 롤백",
        )
    }

    /**
     * R1 적대적 리뷰 결함 — `entries()`가 `LinkedHashMap`을 그대로 돌려주면 적재 순서(`ORDER BY city_id`)
     * 뒤에 신규 도시가 append 되어 `city_id ASC`가 깨진다. 설계안 §8 R3의 공백지화 순회가 그 순서를
     * 전제하므로 반환 시점 정렬을 고정한다.
     */
    @Test
    fun `entries는 신규 도시를 만진 뒤에도 city_id 오름차순이다`() {
        jdbc.update(
            "INSERT INTO v2_city_ledger (world_id, city_id, gold, rice, garrison) " +
                "VALUES (1, 40, 1, 1, 1), (1, 60, 1, 1, 1)",
            MapSqlParameterSource(),
        )
        val store = V2CityLedgerStore(jdbc)
        val recorder = recorder()
        // 40/60 적재 후 그 사이 번호(50)를 처음 만진다 — LinkedHashMap이면 40,60,50 순이 된다.
        store.adjust(worldId, recorder, cityId = 50, goldDelta = 5)

        val ordered = store.entries(worldId).keys.filter { it in 40..60 }
        assertEquals(listOf(40, 50, 60), ordered, "city_id ASC 유지: ${store.entries(worldId).keys}")

        flush(186, recorder)
        assertEquals(5L, ledgerRow(50)!!["gold"])
    }

    @Test
    fun `v2 채널이 비면 v2 step이 미진입한다 -- v1 경로 SQL 0건`() {
        executor.flush(FlushPayload(worldId, worldState(190)))
        assertTrue(
            executor.lastOps().none { it.table == "v2_city_ledger" },
            "빈 컬렉션 가드: ${executor.lastOps()}",
        )
        assertEquals(190, currentYear())
    }
}
