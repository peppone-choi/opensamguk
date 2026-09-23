package opensamguk.engine.boot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.hwiha.HwihaEncounterResolver
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.seed.HanWorldArtifactsResolver
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * S3 관문 — 운영 배선(Spring 컨텍스트의 TurnRunService: 개인 턴 lifecycle·NPC 선택기·순 경계·월간 파이프라인)으로
 * 豫州 조각을 48순 돌려 출사 → 발령 → 행군 → 조우 → 공성 → 점령 → 징세 → 월단평이 스스로 이어지는지 본다.
 * 사람이 하는 일은 가입·장수 생성·출사 예약뿐이고, 나머지는 NPC 와 루프가 한다. 적색 짝은 [HwihaS3PassChainProbeIT].
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
class HwihaS3PassChainIT {
    @Autowired lateinit var world: InMemoryTurnWorld
    @Autowired lateinit var service: TurnRunService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var loader: WorldSnapshotLoader

    @Test
    fun `S3 고리 — 출사 발령 행군 조우 공성 점령 징세 월단평이 관리자 개입 없이 이어진다`() {
        HwihaS3ChainSupport.run(service)
        HwihaS3ChainSupport.assertChain(world, jdbc, WORLD)
        HwihaS3ChainSupport.assertSiegesReload(world, loader)
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
        private const val WORLD = 23

        private fun repoRoot(): Path = HwihaS3ChainSupport.repoRoot()

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
            System.setProperty("opensamguk.artifacts.root", repoRoot().toString())
            // 부팅 전에 심는다 — InMemoryTurnWorld 빈이 부팅 시점의 DB 를 읽는다.
            val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
            HwihaS3ChainSupport.seed(JdbcTemplate(source), WORLD, withUnits = true)

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
