package opensamguk.gameapi.reserve

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.wire.*
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.CommandController
import opensamguk.infra.persistence.*
import opensamguk.logic.input.RuleProfile
import org.junit.jupiter.api.*
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.MethodParameter
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@TestConfiguration(proxyBeanMethods = false)
@EnableWebMvc
class ReservationSlotHttpConfiguration : WebMvcConfigurer {
    @Bean fun serverFactory() = TomcatServletWebServerFactory(0).apply { address = InetAddress.getLoopbackAddress() }
    @Bean fun commandController() = controller
    @Bean fun dispatcherRegistration(context: WebApplicationContext) =
        ServletRegistrationBean(DispatcherServlet(context), "/").apply { setLoadOnStartup(1) }
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        // Isolated fixture user only; this probe does not validate JWT/security middleware.
        resolvers += object : HandlerMethodArgumentResolver {
            override fun supportsParameter(parameter: MethodParameter) =
                parameter.hasParameterAnnotation(AuthenticationPrincipal::class.java)
            override fun resolveArgument(parameter: MethodParameter, container: ModelAndViewContainer?,
                request: NativeWebRequest, binder: WebDataBinderFactory?): Any = 42L
        }
    }
    companion object { lateinit var controller: CommandController }
}

/** Real HTTP intake and durable repositories, with isolated containers and fixture-only ownership reads. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationSlotBoundaryIT {
    private val pg = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    private val redisContainer: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)
    private lateinit var jdbc: JdbcTemplate
    private lateinit var redis: StringRedisTemplate
    private lateinit var redisFactory: LettuceConnectionFactory
    private lateinit var context: AnnotationConfigServletWebServerApplicationContext
    private lateinit var service: CommandReserveService
    private var requestSequence = 0
    private var fixedRequestId: String? = null
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private val streamKey = TurnDaemonStreamKeys.of("qa-slot-intake", WorldId(1)).commandStream

    @BeforeAll fun startIsolatedFixture() {
        pg.start()
        redisContainer.start()
        val dataSource = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
        jdbc = JdbcTemplate(dataSource)
        checkNotNull(javaClass.getResource("/reserve-slot-boundary-schema.sql")).readText()
            .split(";").filter { it.isNotBlank() }.forEach { jdbc.execute(it) }
        jdbc.execute(checkNotNull(ReservedTurnRepository::class.java.getResource(
            "/db/migration/V77__general_turn_reservation_revision.sql")).readText())
        jdbc.update("INSERT INTO world_state(id,config) VALUES (1,'{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}'::jsonb)")
        jdbc.update("INSERT INTO general(world_id,id) VALUES (1,10)")
        val params = NamedParameterJdbcTemplate(dataSource)
        redisFactory = LettuceConnectionFactory(RedisStandaloneConfiguration(redisContainer.host,
            redisContainer.getMappedPort(6379))).apply { afterPropertiesSet() }
        redis = StringRedisTemplate(redisFactory).apply { afterPropertiesSet() }
        // Only world/owner READ seams are fixture stubs. Intake, JDBC writes, Redis and HTTP are real.
        val worlds = mock(WorldStateReadRepository::class.java)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")))
        val generals = mock(GeneralReadRepository::class.java)
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10, worldId = 1, userId = "42")))
        val resolver = mock(GeneralResolver::class.java)
        `when`(resolver.resolveGeneralId(42L)).thenReturn(10)
        val inbox = CommandInboxRepository(params)
        val results = CommandResultRepository(params)
        service = CommandReserveService(ReservedTurnRepository(params), inbox, results, redis,
            mock(), GameApiProcessWorld(1), "qa-slot-intake",
            requestIds = { fixedRequestId ?: "slot-request-${++requestSequence}" },
            transactions = TransactionTemplate(DataSourceTransactionManager(dataSource)), worldStates = worlds,
            captiveAdmission = CaptiveAdmission(generals), siegeAssaultAdmission = SiegeAssaultAdmission(mock(SiegeReader::class.java)))
        ReservationSlotHttpConfiguration.controller = CommandController(mock(CommandPrecheckService::class.java), service,
            resolver, mock(CommandQueueService::class.java), generals, results, inbox, redis, mapper,
            "qa-slot-intake", GameApiProcessWorld(1), worlds)
        context = AnnotationConfigServletWebServerApplicationContext()
        context.register(ReservationSlotHttpConfiguration::class.java)
        context.refresh()
    }

    @BeforeEach fun clearOwnFixture() {
        for (table in listOf("general_turn", "command_inbox", "command_result", "command_outbox")) {
            jdbc.execute("TRUNCATE TABLE $table")
        }
        redis.keys("*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
        requestSequence = 0
        fixedRequestId = null
    }

    private fun arguments(code: String) =
        if (code == "action.siegeRoadFort") """{"fortId":"qa-fort-1"}""" else "{}"

    private fun submit(code: String, turnIdx: String?): HttpResponse<String> {
        val slotQuery = turnIdx?.let { "&turnIdx=$it" }.orEmpty()
        val uri = URI.create("http://127.0.0.1:${context.webServer.port}/api/command/$code?generalId=10$slotQuery")
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(arguments(code))).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun reserve(code: String, slot: Int, verified: Boolean): CommandReserveService.ReserveResult =
        if (verified) service.reserveForOwnerWithRuleProfile(10, code, slot, arguments(code), 42, RuleProfile.HWIHA)
        else service.reserveForOwner(10, code, slot, arguments(code), 42)

    private fun assertStored(code: String, slot: Int, request: String) {
        val row = jdbc.queryForMap("SELECT turn_idx, action_code, arg::text FROM general_turn WHERE request_id=?", request)
        assertEquals(slot, row["turn_idx"])
        assertEquals(code, row["action_code"])
        assertEquals(mapper.readTree(arguments(code)), mapper.readTree(row["arg"].toString()))
        assertEquals(slot, jdbc.queryForObject("SELECT turn_idx FROM command_inbox WHERE request_id=?",
            Int::class.java, request))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE request_id=?",
            Int::class.java, request))
        val payload = jdbc.queryForObject("SELECT result_payload::text FROM command_result WHERE request_id=?",
            String::class.java, request)
        assertTrue(checkNotNull(payload).contains("reservationAccepted"))
        val wake = redis.opsForStream<Any, Any>().read(StreamOffset.create(streamKey, ReadOffset.from("0"))).orEmpty()
            .map { decodeCommandEnvelope(it.value[WIRE_PAYLOAD_FIELD].toString()) }.single { it.requestId == request }
        assertEquals(TurnDaemonCommand.Run(RunReason.POKE), wake.command)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_inbox WHERE request_id=? AND command_kind='IMMEDIATE'",
            Int::class.java, "$request:presence"))
    }

    private data class State(val postgres: List<List<String>>, val redis: Map<String, String>, val requests: Int)

    private fun state(): State {
        // Compare complete rows and every Redis value, including receipt, presence and wake markers.
        val rows = listOf("general_turn", "command_inbox", "command_result", "command_outbox").map { table ->
            jdbc.queryForList("SELECT row_to_json(t)::text FROM $table t ORDER BY row_to_json(t)::text", String::class.java)
        }
        val values = redis.keys("*").sorted().associateWith { Base64.getEncoder().encodeToString(redis.dump(it)) }
        return State(rows, values, requestSequence)
    }

    private fun assertBlocked(code: String, slot: Int) {
        val before = state()
        val response = submit(code, slot.toString())
        assertEquals(200, response.statusCode())
        val body = mapper.readTree(response.body())
        assertEquals("BLOCKED", body["status"].textValue())
        assertEquals("INVALID_TURN_SLOT", body["code"].textValue())
        assertEquals("예약 순은 0부터 11까지입니다.", body["reason"].textValue())
        assertEquals(before, state())
        println("GUARDED code=$code rawTurn=$slot HTTP=200 code=INVALID_TURN_SLOT PG=unchanged Redis=unchanged")
    }

    @Test fun `original six defects and three assault controls reject without changing seeded reservations`() {
        for (code in commands) {
            val seed = reserve(code, 7, false)
            assertStored(code, 7, seed.requestId)
            for (slot in listOf(-1, 12, Int.MAX_VALUE)) assertBlocked(code, slot)
        }
        for (slot in listOf(-1, 12, Int.MAX_VALUE)) assertBlocked("action.assault", slot)
    }

    @Test fun `original twenty four HTTP controls keep all twelve slots and default zero`() {
        for (code in commands) {
            for (slot in 0..11) {
                val response = submit(code, slot.toString())
                assertEquals(202, response.statusCode())
                val body = mapper.readTree(response.body())
                assertEquals("AVAILABLE", body["status"].textValue())
                assertEquals(slot, body["turnIdx"].intValue())
                assertStored(code, slot, body["requestId"].textValue())
                println("NORMAL_CONTROL code=$code rawTurn=$slot HTTP=202 DB_slot=$slot Redis=Run(POKE)")
            }
            val response = submit(code, null)
            assertEquals(202, response.statusCode())
            val body = mapper.readTree(response.body())
            assertEquals(0, body["turnIdx"].intValue())
            assertStored(code, 0, body["requestId"].textValue())
        }
    }

    @Test fun `both service entry paths accept all twelve slots`() {
        for (verified in listOf(false, true)) for (code in commands) for (slot in 0..11) {
            val result = reserve(code, slot, verified)
            assertEquals(slot, result.turnIdx)
            assertStored(code, slot, result.requestId)
        }
    }

    @Test fun `both service entry paths reject bounds and integer extremes before persistence`() {
        for (verified in listOf(false, true)) for (code in commands) {
            reserve(code, 7, verified)
            for (slot in invalidSlots) {
                val before = state()
                val failure = assertFailsWith<AdmissionDenied> { reserve(code, slot, verified) }
                assertEquals("INVALID_TURN_SLOT", failure.code)
                assertEquals("예약 순은 0부터 11까지입니다.", failure.message)
                assertEquals(before, state())
            }
        }
    }

    @Test fun `HTTP rejects remaining extremes and binding overflow without any writes`() {
        for (code in commands) {
            reserve(code, 7, false)
            for (slot in listOf(Int.MIN_VALUE, -2, 30)) assertBlocked(code, slot)
            for (raw in listOf("2147483648", "-2147483649", "999999999999999999999999")) {
                val before = state()
                assertEquals(400, submit(code, raw).statusCode())
                assertEquals(before, state())
            }
        }
    }

    @Test fun `valid replacement and identical request retry preserve existing semantics on both paths`() {
        for (verified in listOf(false, true)) for (code in commands) for (slot in listOf(0, 7, 11)) {
            val first = reserve(code, slot, verified)
            val replacement = reserve(code, slot, verified)
            assertTrue(first.requestId != replacement.requestId)
            assertStored(code, slot, replacement.requestId)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE request_id=?",
                Int::class.java, first.requestId))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE request_id=?",
                Int::class.java, first.requestId))
            fixedRequestId = replacement.requestId
            val before = state()
            assertEquals(replacement, reserve(code, slot, verified))
            assertEquals(before, state())
            val rejectedRetry = assertFailsWith<AdmissionDenied> { reserve(code, -1, verified) }
            assertEquals("INVALID_TURN_SLOT", rejectedRetry.code)
            assertEquals(before, state())
            fixedRequestId = null
        }
    }

    @Test fun `authentication and argument validation retain their order`() {
        for (code in commands) {
            val before = state()
            val unauthorized = assertFailsWith<AdmissionDenied> {
                service.reserveForOwner(10, code, -1, "invalid", 0)
            }
            assertEquals("UNAUTHORIZED", unauthorized.code)
            val invalidSlot = assertFailsWith<AdmissionDenied> {
                service.reserveForOwner(10, code, -1, "invalid", 42)
            }
            assertEquals("INVALID_TURN_SLOT", invalidSlot.code)
            val invalidArguments = assertFailsWith<AdmissionDenied> {
                service.reserveForOwner(10, code, 0, "invalid", 42)
            }
            assertEquals("INVALID_REQUEST", invalidArguments.code)
            assertEquals(before, state())
        }
    }

    private val commands = listOf("action.demandSurrender", "action.siegeRoadFort")
    private val invalidSlots = listOf(-2, -1, 12, 30, Int.MIN_VALUE, Int.MAX_VALUE)

    @AfterAll fun stopOwnFixture() {
        if (::context.isInitialized) context.close()
        if (::redisFactory.isInitialized) redisFactory.destroy()
        redisContainer.stop()
        pg.stop()
    }
}
