package opensamguk.engine.boot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.hwiha.HwihaMonthlyAssessment
import opensamguk.engine.hwiha.HwihaMonthlyCountyIncome
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import java.nio.file.Files
import java.nio.file.Path
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.world.HanWorldVariant
import opensamguk.logic.input.HwihaPersonPolicyState
import opensamguk.logic.input.HwihaRenownAssessment
import opensamguk.logic.input.HwihaRenownRules
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 월 경계 징세가 **프로덕션 배선을 거쳐** 스스로 도는지 본다.
 *
 * 기존 HWIHA 테스트는 전부 [HwihaMonthlyCountyIncome] 을 직접 부른다. 그런데 실제 호출처는
 * TurnRunService 의 월 경계 블록이고, 그 블록은 `pipeline != null && eventDispatcher != null`
 * 일 때만 돈다 — HwihaEnlistmentFixture.service() 는 둘 다 넘기지 않으므로 그 경로를 한 번도
 * 지나지 않았다. 즉 배선 자체가 미검증이었다. 여기서는 엔진 Spring 컨텍스트를 띄워
 * `@Bean TurnRunService`(파이프라인·이벤트 디스패처가 실제로 물린 것)를 받아 runTick 으로
 * 월 경계를 넘긴다. 관리자 개입은 縣 하나에 창고를 두는 것뿐이고, 징세는 루프가 스스로 한다.
 *
 * 정본 설계 §15.2 S3 관문의 「징세」 고리에 해당한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
    ],
)
class HwihaMonthBoundaryLoopIT {
    @Autowired lateinit var world: InMemoryTurnWorld
    @Autowired lateinit var service: TurnRunService

    @Test
    fun `루프가 월 경계를 넘으며 스스로 縣 창고에 세입을 넣고 같은 달을 두 번 넣지 않는다`() {
        // 시드가 이 縣 을 세력 수도로 두고 창고를 얹었다(companion). 여기서는 루프만 돈다.
        val county = requireNotNull(seededCounty) { "시드가 縣 을 고르지 않았다" }
        val before = assertNotNull(
            HwihaCountyWarehouse.read(assertNotNull(world.getCityById(county)).meta, county),
            "시드한 창고가 스냅샷에 실렸다",
        )
        assertEquals(HwihaResources(), before.stock, "시작 재고는 비어 있다")
        assertNull(
            world.getState().meta[HwihaMonthlyCountyIncome.STAMP_KEY],
            "아직 어떤 달도 징세되지 않았다",
        )

        // 한 달 = phasesPerMonth(3)순, 1순 = tick_seconds/60 = 60분. 3순 뒤가 200년 2월 상순이다.
        service.runTick(Instant.parse("0200-01-01T03:00:00Z"))

        assertEquals(
            HwihaMonthlyCountyIncome.stampOf(200, 2),
            world.getState().meta[HwihaMonthlyCountyIncome.STAMP_KEY],
            "루프가 월 경계를 넘으며 스스로 징세 도장을 찍었다",
        )
        val city = assertNotNull(world.getCityById(county))
        assertTrue(city.supplyState != 0, "월간 보급 BFS 가 이 縣 을 보급 안에 두었다")
        val credited = assertNotNull(HwihaCountyWarehouse.read(city.meta, county), "창고가 그대로 있다")
        assertTrue(credited.stock.grain > 0, "곡이 들어왔다: ${credited.stock}")
        assertEquals(1, credited.revision, "정확히 한 번 적립됐다")

        // ── 월단평: 같은 월 경계에서 명망이 갱신되고 순위가 발표됐다 ──────────────────────────
        assertEquals(
            HwihaMonthlyAssessment.stampOf(200, 2),
            world.getState().meta[HwihaMonthlyAssessment.STAMP_KEY],
            "루프가 월단평도 스스로 돌렸다",
        )
        val lord = assertNotNull(world.getGeneralById(1))
        assertEquals(
            HwihaRenownRules.INITIAL_CAPACITY + 2 * HwihaRenownAssessment.CANON.warMerit,
            assertNotNull(HwihaPersonPolicyState.read(lord.meta)).renownCapacity,
            "전공 2건이 명망을 올렸다",
        )
        assertNull(
            lord.meta[HwihaMonthlyAssessment.TALLY_META_KEY],
            "적용한 집계는 비워야 한다 — 남기면 다음 달에 또 적용된다",
        )
        val peer = assertNotNull(world.getGeneralById(2))
        assertEquals(
            HwihaRenownRules.INITIAL_CAPACITY,
            assertNotNull(HwihaPersonPolicyState.read(peer.meta)).renownCapacity,
            "사건이 없는 장수는 명망을 보존한다 — 월단평은 초기화하지 않는다",
        )
        @Suppress("UNCHECKED_CAST")
        val ranking = world.getState().meta[HwihaMonthlyAssessment.RANKING_KEY] as? List<Int>
        assertEquals(
            1, assertNotNull(ranking, "순위가 발표됐다").first(),
            "명망이 가장 높은 장수가 1 위다",
        )

        // 같은 달 안에서 또 tick 해도 도장에 막혀 두 번 들어가지 않는다.
        service.runTick(Instant.parse("0200-01-01T04:00:00Z"))
        assertEquals(
            credited,
            HwihaCountyWarehouse.read(assertNotNull(world.getCityById(county)).meta, county),
            "같은 달을 두 번 적립하지 않는다",
        )
        assertEquals(
            HwihaRenownRules.INITIAL_CAPACITY + 2 * HwihaRenownAssessment.CANON.warMerit,
            assertNotNull(HwihaPersonPolicyState.read(assertNotNull(world.getGeneralById(1)).meta)).renownCapacity,
            "월단평도 같은 달을 두 번 적용하지 않는다",
        )
    }

    /**
     * `WorldSnapshotLoader` 의 산출물 resolver 는 `private companion object` 의 val 이라 그 클래스가
     * 처음 로드될 때 루트가 박힌다. 이 모듈의 테스트는 한 JVM 에서 1200건 넘게 돌므로 다른 테스트가
     * 먼저 클래스를 로드하면 루트가 `.` 으로 굳어 시스템 프로퍼티가 늦는다(CI 에서만 빨개졌다).
     * 그래서 프로퍼티에 기대지 않고 빈을 덮어 저장소 루트를 명시적으로 넘긴다.
     */
    @TestConfiguration
    class ArtifactsRootConfig {
        @Bean
        fun worldSnapshotLoader(
            jdbc: JdbcTemplate,
            seedBootstrap: SeedBootstrap,
            processWorld: EngineProcessWorld,
        ): WorldSnapshotLoader {
            val artifacts = HanWorldArtifactsResolver(repoRoot())
            return WorldSnapshotLoader(
                jdbc, seedBootstrap, processWorld.worldId,
                waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
                hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
                administrativeCountyIdsLoader = { artifacts.artifacts(it).projection.administrativeCountyIds },
                cityLandProvinceLoader = { variant ->
                    artifacts.artifacts(variant).projection.bindingsByCityId
                        .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap()
                },
            )
        }
    }

    companion object {
        private const val WORLD = 17

        @JvmStatic
        private var seededCounty: Int? = null

        /** 작업 디렉터리가 어디든 `data/map` 을 가진 저장소 루트를 찾아 절대경로로 돌려준다. */
        private fun repoRoot(): Path {
            val from = Path.of("").toAbsolutePath()
            var at: Path? = from
            while (at != null && !Files.isDirectory(at.resolve("data/map"))) at = at.parent
            return requireNotNull(at) { "data/map 을 가진 저장소 루트를 $from 위에서 찾지 못했다" }
        }

        /**
         * Gradle 은 이 모듈의 테스트를 한 JVM 에서 돌린다. 루트 프로퍼티를 남기면 뒤따르는
         * 테스트의 기본 산출물 루트까지 바뀌므로 이 클래스가 끝날 때 되돌린다.
         */
        @JvmStatic
        @AfterAll
        fun clearArtifactsRoot() {
            System.clearProperty("opensamguk.artifacts.root")
        }

        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @Container @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            // 엔진은 저장소 루트에서 도는 것을 전제로 지도 산출물을 `.` 아래에서 찾는다. Gradle 의
            // 테스트 작업 디렉터리는 그 전제와 다를 수 있고(로컬은 app/game-engine, CI 는 또 달랐다)
            // 상대경로를 박으면 한쪽에서만 맞는다. data/map 이 보일 때까지 올라가 절대경로로 못박는다.
            System.setProperty("opensamguk.artifacts.root", repoRoot().toString())

            // 컨텍스트가 뜨기 전에 월드를 심는다 — BootstrapConfig 의 InMemoryTurnWorld 빈이
            // 부팅 시점의 DB 를 읽으므로, 부팅 뒤에 넣으면 루프가 빈 월드를 돈다.
            val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
            val jdbc = JdbcTemplate(source)
            HwihaEnlistmentFixture(
                jdbc,
                JdbcFlushExecutor(
                    NamedParameterJdbcTemplate(source),
                    TransactionTemplate(DataSourceTransactionManager(source)),
                ),
            ).seed(WORLD)
            // startYear·startTime 이 없으면 boundaryDate 가 startTime 기본값 Instant.now() 로
            // 떨어져 날짜가 실행 시각마다 달라진다. 결정적으로 고정한다.
            jdbc.update(
                """UPDATE world_state
                   SET meta = meta || '{"startYear":200,"startTime":"0200-01-01T00:00:00Z"}'::jsonb
                   WHERE id=?""",
                WORLD,
            )

            // 세입은 소유·보급된 縣 에만 들어간다. 그런데 보급 상태는 월간 파이프라인의
            // UpdateCitySupply 가 수도에서 BFS 로 다시 계산하므로, 시드에서 supply_state 를
            // 1 로 박아도 경계에서 덮인다. 그래서 이 縣 을 세력 수도로 만든다.
            val county = HanWorldArtifactsResolver(Path.of("../.."))
                .artifacts(HanWorldVariant.V3_1133).projection.administrativeCountyIds.min()
            seededCounty = county
            jdbc.update(
                "UPDATE city SET nation_id=1, supply_state=1, meta=?::jsonb WHERE world_id=? AND id=?",
                MetaJson.encode(
                    mapOf(
                        "keep" to "unchanged",
                        HwihaCountyWarehouse.META_KEY to
                            HwihaCountyWarehouse(county, 0, HwihaResources()).toMetaValue(),
                    ),
                ),
                WORLD, county,
            )
            // 보급 BFS 는 `level > 0` 인 세력의 수도만 씨앗으로 쓴다(WorldActionContext.capitals).
            // 픽스처의 nation 은 level 기본값 0 이라 그대로면 어떤 縣 도 보급되지 않는다.
            jdbc.update(
                "UPDATE nation SET capital_city_id=?, level=1 WHERE world_id=? AND id=1",
                county, WORLD,
            )

            // 월단평 집계를 장수 1 에 얹는다. 조우·점령 원천은 다른 흐름이 붙이므로, 게이트가 갱신 기계 전체를
            // 지나게 하려면 집계를 직접 심어야 한다. 한 달에 종류당 한 건이라 전공 2건은 서로 다른 두 달이다
            // (199-12, 200-01 — 둘 다 200-02 월단평 이전) → CANON 에서 +3*2.
            jdbc.update(
                """UPDATE general SET meta = meta || '{"hwihaRenownTally":{"entries":[
                     {"kind":"warMerit","stamp":"0199-12","source":"ENCOUNTER_VICTORY"},
                     {"kind":"warMerit","stamp":"0200-01","source":"COUNTY_CAPTURE"}]}}'::jsonb
                   WHERE world_id=? AND id=1""",
                WORLD,
            )

            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("management.health.redis.enabled") { "false" }
            registry.add("OPENSAMGUK_WORLD_ID") { "$WORLD" }
            registry.add("SCENARIO_SEED_ENABLED") { "false" }
            // 데몬 스레드가 같은 월드를 동시에 돌면 이 테스트의 tick 과 경쟁한다. 직접 몬다.
            registry.add("opensamguk.daemon.enabled") { "false" }
        }
    }
}
