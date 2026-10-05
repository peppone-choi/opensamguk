package opensamguk.engine.admission

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.gameapi.security.GameApiSecurityConfig
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.gameapi.security.ServerAdmissionConfiguration
import opensamguk.gameapi.security.ServerAdmissionPolicy
import opensamguk.gameapi.sse.RealtimeRelayController
import opensamguk.gateway.publication.api.ServerPublicationController
import opensamguk.gateway.publication.api.ServerPublicationErrorHandler
import opensamguk.gateway.publication.application.ListPublicServers
import opensamguk.gateway.publication.application.ReadServerAdmission
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import opensamguk.gateway.publication.domain.VerifyServerPublication
import opensamguk.gateway.publication.infra.JdbcServerPublicationRepository
import opensamguk.gateway.publication.infra.JdbcServerPublicationWriter
import opensamguk.gateway.publication.infra.UnavailableServerPublicationReceiptVerifier
import opensamguk.gateway.security.CustomUserDetailsService
import opensamguk.gateway.security.GatewayJwtProperties
import opensamguk.gateway.security.GatewayJwtSigningMode
import opensamguk.gateway.security.InternalServiceTokenFilter
import opensamguk.gateway.security.JwtAuthenticationFilter
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.gateway.security.SecurityConfig
import opensamguk.gateway.service.ServerRegistry
import opensamguk.infra.read.UserRepository
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Proxy
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthetic membership, account key and write target; real PostgreSQL, migration,
 * publisher JDBC, service authentication, Spring HTTP, admission consumer and SSE.
 * This fixture supplies no production reset authority or publication PASS receipt.
 */
internal class AdmissionProducerConsumerFixture private constructor(
    val jdbc: JdbcTemplate,
) : AutoCloseable {
    val mapper = ObjectMapper().findAndRegisterModules()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    val serviceToken = "admission-it-${UUID.randomUUID()}"
    val issuer = JwtTokenProvider(GatewayJwtProperties().apply {
        signingMode = GatewayJwtSigningMode.RS256
        privateKey = Base64.getEncoder().encodeToString(keys.private.encoded)
        publicKey = this@AdmissionProducerConsumerFixture.publicKey
    })
    val registry = ServerRegistry("", mapper, jdbc)
    val repository = JdbcServerPublicationRepository(jdbc, registry)
    val writer = JdbcServerPublicationWriter(jdbc, repository, UnavailableServerPublicationReceiptVerifier())
    val downstreamCalls = AtomicInteger()
    val unexpectedAccountLookups = AtomicInteger()
    private val contexts = mutableListOf<ServletWebServerApplicationContext>()
    val gateway = start(GatewayFixtureConfiguration::class.java, emptyMap())
    val gatewayOrigin = "http://127.0.0.1:${gateway.webServer.port}"
    val consumer = startConsumer()
    val consumerOrigin = origin(consumer)

    fun token(role: String = "USER") = issuer.generateAccessToken(42L, role)
    fun countWrites(): Int = requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM admission_it_write", Int::class.java))

    fun sqlSnapshot(): Map<String, Any> = mapOf(
        "registeredRows" to requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM game_server", Int::class.java)),
        "publication" to jdbc.queryForList("SELECT state, revision FROM game_server_publication WHERE server_id='pep'"),
        "writes" to countWrites(),
        "downstreamCalls" to downstreamCalls.get(),
        "unexpectedAccountLookups" to unexpectedAccountLookups.get(),
    )

    fun enterVerifying(): Long = writer.verifying(VerifyServerPublication(
        "pep", 1L, ServerPublicationTarget(UUID.randomUUID().toString().replace("-", ""), 0, "scenario_3190", "e".repeat(64)),
    )).revision

    fun startConsumer(presentedServiceToken: String = serviceToken) = start(ConsumerFixtureConfiguration::class.java, mapOf(
        "server-admission.gateway-origin" to gatewayOrigin,
        "server-admission.server-id" to "pep",
        "server-admission.service-token" to presentedServiceToken,
    ))

    fun relay(context: ServletWebServerApplicationContext = consumer): RealtimeRelayController =
        context.getBean(RealtimeRelayController::class.java)

    // The gateway login/account path is deliberately outside this fixture. Any
    // accidental account lookup fails instead of returning a fabricated account.
    fun unusedAccountRepository(): UserRepository = Proxy.newProxyInstance(
        UserRepository::class.java.classLoader, arrayOf(UserRepository::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "UnusedAdmissionAccountRepository"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> {
                unexpectedAccountLookups.incrementAndGet()
                error("Gateway account lookup is outside the admission service-auth fixture")
            }
        }
    } as UserRepository

    private fun start(type: Class<*>, properties: Map<String, Any>): ServletWebServerApplicationContext {
        val context = try { SpringApplicationBuilder(type).initializers({ application ->
            application.beanFactory.registerSingleton("admissionFixture", this)
            application.environment.propertySources.addFirst(MapPropertySource("admission-isolated-it", mapOf(
                "server.port" to 0,
                "server.address" to "127.0.0.1",
                "spring.main.banner-mode" to "off",
                "spring.main.register-shutdown-hook" to false,
                "spring.jmx.enabled" to false,
                "management.endpoints.enabled-by-default" to false,
                "sentry.dsn" to "",
            ) + properties))
        }).run() as ServletWebServerApplicationContext } catch (failure: Throwable) {
            contexts.asReversed().forEach { it.close() }
            throw failure
        }
        contexts += context
        return context
    }

    override fun close() {
        contexts.asReversed().forEach { it.close() }
    }

    companion object {
        fun origin(context: ServletWebServerApplicationContext) = "http://127.0.0.1:${context.webServer.port}"

        fun open(jdbc: JdbcTemplate): AdmissionProducerConsumerFixture {
            jdbc.execute("""CREATE TABLE game_server (
                sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                server_id VARCHAR(48) PRIMARY KEY, display_name TEXT NOT NULL,
                game_api_url TEXT NOT NULL, game_engine_url TEXT NOT NULL, deploy_project TEXT NOT NULL,
                generation INTEGER, scenario_code TEXT
            )""")
            jdbc.update("""INSERT INTO game_server (server_id, display_name, game_api_url, game_engine_url,
                deploy_project, generation, scenario_code) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                "pep", "격리 입장 시험", "http://spep-game-api:8081", "http://spep-game-engine:8082", "opensamguk-spep", 0, "scenario_3190")
            val migrations = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__game_server_publication.sql")
            check(migrations.size == 1) { "Require exactly one actual publication migration, not copied fixture SQL" }
            requireNotNull(jdbc.dataSource).connection.use { ScriptUtils.executeSqlScript(it, migrations.single()) }
            jdbc.execute("CREATE TABLE game_server_registry_seed_state (id SMALLINT PRIMARY KEY, initialized BOOLEAN NOT NULL)")
            jdbc.update("INSERT INTO game_server_registry_seed_state (id, initialized) VALUES (1, TRUE)")
            jdbc.execute("CREATE TABLE admission_it_write (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id BIGINT NOT NULL)")
            return AdmissionProducerConsumerFixture(jdbc)
        }

        fun dataSource(url: String, username: String, password: String) = DriverManagerDataSource(url, username, password)
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class,
    RedisAutoConfiguration::class, RedisRepositoriesAutoConfiguration::class, FlywayAutoConfiguration::class,
    UserDetailsServiceAutoConfiguration::class])
@Import(SecurityConfig::class, ServerPublicationController::class, ServerPublicationErrorHandler::class,
    ListPublicServers::class, ReadServerAdmission::class)
internal class GatewayFixtureConfiguration {
    @Bean fun repository(fixture: AdmissionProducerConsumerFixture) = fixture.repository
    @Bean fun issuer(fixture: AdmissionProducerConsumerFixture) = fixture.issuer
    @Bean fun users(fixture: AdmissionProducerConsumerFixture) = CustomUserDetailsService(fixture.unusedAccountRepository())
    @Bean fun passwordEncoder() = BCryptPasswordEncoder()
    @Bean fun serviceFilter(fixture: AdmissionProducerConsumerFixture) = InternalServiceTokenFilter(fixture.serviceToken)
    @Bean fun jwtFilter(issuer: JwtTokenProvider, users: CustomUserDetailsService) = JwtAuthenticationFilter(issuer, users)
    @Bean fun serviceServletRegistration(filter: InternalServiceTokenFilter) = FilterRegistrationBean(filter).apply { isEnabled = false }
    @Bean fun jwtServletRegistration(filter: JwtAuthenticationFilter) = FilterRegistrationBean(filter).apply { isEnabled = false }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class,
    RedisAutoConfiguration::class, RedisRepositoriesAutoConfiguration::class, FlywayAutoConfiguration::class,
    UserDetailsServiceAutoConfiguration::class])
@Import(GameApiSecurityConfig::class, ServerAdmissionConfiguration::class)
internal class ConsumerFixtureConfiguration {
    @Bean fun verifier(fixture: AdmissionProducerConsumerFixture) = GameApiJwtVerifier(fixture.publicKey, "", "")
    @Bean fun jwtFilter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
    @Bean fun jwtServletRegistration(filter: JwtVerifyFilter) = FilterRegistrationBean(filter).apply { isEnabled = false }
    @Bean fun relay(policy: ServerAdmissionPolicy) = RealtimeRelayController(policy)
    @Bean fun writes(fixture: AdmissionProducerConsumerFixture) = AdmissionWriteFixtureController(fixture)
}

@RestController
@TestComponent
internal class AdmissionWriteFixtureController(private val fixture: AdmissionProducerConsumerFixture) {
    @PostMapping("/api/command/admission-fixture")
    fun write(request: HttpServletRequest): Map<String, Long> {
        fixture.downstreamCalls.incrementAndGet()
        val principal = requireNotNull(JwtVerifyFilter.principal(request))
        fixture.jdbc.update("INSERT INTO admission_it_write (user_id) VALUES (?)", principal.userId)
        return mapOf("userId" to principal.userId)
    }
}
