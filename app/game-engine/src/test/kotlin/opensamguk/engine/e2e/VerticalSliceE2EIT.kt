package opensamguk.engine.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import opensamguk.common.constants.GameConst
import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.gameEventChannel
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.v2.V2CityLedgerStore
import opensamguk.engine.v2.V2CityTransportHandler
import opensamguk.engine.v2.V2GarrisonRecruitHandler
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.precheck.PrecheckStateViewFactory
import opensamguk.gameapi.v2.V2CommandPrecheckService
import opensamguk.gameapi.read.CityReadRawRepository
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRawRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadRawRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRawRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRawRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.sse.RealtimeRelayController
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import opensamguk.logic.v2.command.V2GarrisonRecruitArgs
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import jakarta.persistence.EntityManagerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.Test as KTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P1 Task G4 — the END-TO-END VERTICAL-SLICE GATE (design §12 step 8). The FINAL P1 gate: the full
 * `api → Redis → daemon → flush → turnCompleted-SSE` round-trip, then a flushed-row / jsonb
 * byte-compare against the PHP-captured golden DB dump.
 *
 * Drives every REAL call site against ONE shared Testcontainers world (postgres:16 + redis:7):
 *
 *  1. **game-api precheck** — the REAL [CommandPrecheckService] backed by the REAL JPA read repos
 *     (Hibernate over the seeded Testcontainers DB) returns `AVAILABLE` for `che_상업투자` on the
 *     golden general no.76 BEFORE state.
 *  2. **game-api reserve** — the REAL [CommandReserveService] writes the `general_turn` ring row AND
 *     pokes the daemon (the EXISTING P0-B control signal) on the Redis command stream.
 *  3. **game-engine daemon** — the REAL [TurnRunService] drains the command stream, resolves the
 *     reserved `che_상업투자` through the REAL [ReservedTurnHandler] (the SAME `:logic` constraint
 *     library + the SAME six-component RNG seed), and flushes the post-state in ONE JDBC transaction.
 *  4. **turnCompleted → SSE relay** — a `turnCompleted` [RealtimeEvent] is published on the realtime
 *     channel; the REAL game-api [RealtimeRelayController.fanOut] forwards it to a connected
 *     [SseEmitter] (the SSE relay edge), proving the realtime round-trip.
 *  5. **golden DB byte-compare** — the flushed `general` + `city` + `log_entry` rows byte-match the
 *     committed golden DB dump (`golden/p1/che-golden-db.json`): general gold/exp/ded + `meta` jsonb
 *     (KEY ORDER!), city commerce/agriculture/`*_max`/trust, and the action `log_entry.text`.
 *  6. **no spurious writes** — ONLY general + city + log_entry changed (the TruncateContract: the
 *     untouched tables — nation, troop, diplomacy, rank_data, inheritance_*, hall — stay empty).
 *
 * The PHP golden is the BYTE ORACLE. A RED here that differs from the golden is a REAL parity bug to
 * FIX in the implementation — never by editing the golden or weakening an assertion.
 *
 * Testcontainers macOS quirk handled by the build (`api.version=1.44`, `DOCKER_CONTEXT=default`,
 * `RYUK_DISABLED`). Docker-unavailable ⇒ this IT cannot run (the gate is then `blocked`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerticalSliceE2EIT {

    private val profile = "che:scenario_2"
    private val streamKeys = TurnDaemonStreamKeys.of(profile, WorldId(1))
    private val channel = gameEventChannel(profile, WorldId(1))

    // ---- golden fixtures (committed; PHP-captured by G1) — the byte oracle ----
    private val golden: Golden by lazy { loadGolden() }

    // The `commerce_success` golden case (gold 1000→980, comm 7910→8013, exp 3030→3102,
    // ded 2940→3043, intel_exp 0→1, max_domestic_critical 0→51.5; log "상업 투자 성공 103").
    private val caseName = "commerce_success"
    private val action = "che_상업투자"
    private val generalId = 76
    private val cityId = 1
    private val nationId = 2
    private val year = 181
    private val month = 1
    private val startYear = 181
    private val tickSeconds = 3600

    private val t0: Instant = Instant.parse("0181-01-01T12:47:00Z")

    private lateinit var postgres: org.testcontainers.containers.PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var flushExecutor: JdbcFlushExecutor

    private lateinit var emf: EntityManagerFactory
    private lateinit var jpaTx: TransactionTemplate

    private lateinit var redis: org.testcontainers.containers.GenericContainer<*>
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var template: StringRedisTemplate

    @BeforeAll
    fun setUpClass() {
        // --- postgres + Flyway baseline -----------------------------------------------------------
        postgres = org.testcontainers.containers.PostgreSQLContainer("postgres:16-alpine")
            .withStartupTimeout(java.time.Duration.ofMinutes(3))
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration", "classpath:db/migration_v2")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        flushExecutor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))

        // --- REAL JPA read path (Hibernate over the Testcontainers DataSource) --------------------
        // The game-api precheck reads the SAME seeded DB through its REAL JPA read repos — this is the
        // full round-trip (NOT a stubbed read). ddl-auto=validate so the entities are checked against
        // the Flyway baseline. The write path stays JDBC-only; JPA is the legitimate read path (§7).
        emf = buildEntityManagerFactory(dataSource)
        jpaTx = TransactionTemplate(JpaTransactionManager(emf))

        // --- redis (command stream + realtime pub/sub) --------------------------------------------
        redis = org.testcontainers.containers.GenericContainer("redis:7-alpine").withExposedPorts(6379)
        redis.start()
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
        )
        connectionFactory.afterPropertiesSet()
        template = StringRedisTemplate(connectionFactory)
        template.afterPropertiesSet()
    }

    @AfterAll
    fun tearDownClass() {
        if (this::emf.isInitialized) emf.close()
        if (this::connectionFactory.isInitialized) connectionFactory.destroy()
        if (this::redis.isInitialized) redis.stop()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun resetDatabase() {
        jdbc.jdbcOperations.execute("TRUNCATE TABLE world_state CASCADE")
    }

    @KTest
    fun `P1 GATE — api precheck-reserve - daemon resolve-flush - turnCompleted SSE - golden DB byte-compare`() {
        assertTrue(redis.isRunning, "redis:7-alpine container must be running")
        assertTrue(postgres.isRunning, "postgres:16-alpine container must be running")

        // === seed the golden BEFORE state into the DB + the in-memory daemon world ================
        seedDbBeforeState()
        val world = buildWorldBeforeState()

        // === STEP 1: game-api precheck over the REAL JPA read path → AVAILABLE ====================
        val precheckService = buildRealPrecheckService()
        val precheck = jpaTx.execute { precheckService.precheck(generalId, action) }
        assertEquals(PrecheckResult.Available, precheck, "game-api precheck reads the seeded DB → AVAILABLE")

        // === STEP 2: game-api reserve → general_turn ring row + daemon poke on the command stream ==
        val reservedRepo = ReservedTurnRepository(jdbc)
        val reserveService = CommandReserveService(
            reservedTurns = reservedRepo,
            commandInbox = opensamguk.infra.persistence.CommandInboxRepository(jdbc),
            commandResults = opensamguk.infra.persistence.CommandResultRepository(jdbc),
            redis = template,
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = profile,
            clock = Clock.fixed(t0, ZoneOffset.UTC),
            requestIds = { "gate-req-1" },
            transactions = TransactionTemplate(DataSourceTransactionManager(dataSource)),
        )
        val reserveResult = reserveService.reserve(generalId = generalId, actionCode = action, turnIdx = 0)
        assertEquals("gate-req-1", reserveResult.requestId)
        // the durable reservation landed in the ring (the daemon reads THIS, not the Redis poke)
        assertEquals(action, reservedRepo.readReserved(WorldId(1), generalId, 0).actionCode, "reserved action persisted in the ring")

        // === STEP 4 (subscribe BEFORE the tick): wire the REAL SSE relay onto the realtime channel ==
        // A real RedisMessageListenerContainer (mirroring the production RealtimeSubscriber bean) relays
        // each channel message into the REAL RealtimeRelayController.fanOut, which sends it to a
        // connected SseEmitter — proving the turnCompleted reaches the SSE edge, not just the channel.
        val relay = RealtimeRelayController()
        val sseEvents = CopyOnWriteArrayList<String>()
        registerRecordingEmitter(relay, sseEvents)
        val channelMsg = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        container.addMessageListener(
            MessageListener { msg, _ ->
                val body = String(msg.body)
                channelMsg.set(body)
                relay.fanOut(body) // the REAL game-api SSE relay forwards it
                latch.countDown()
            },
            ChannelTopic(channel),
        )
        container.afterPropertiesSet()
        container.start()
        try {
            val subscribeDeadline = System.currentTimeMillis() + 3000
            while (!container.isRunning && System.currentTimeMillis() < subscribeDeadline) Thread.sleep(20)

            // === STEP 3: the daemon drains the stream, resolves che_상업투자, flushes in ONE txn ====
            val registry = CommandRegistry(GeneralActionPipeline())
            val handler = ReservedTurnHandler(world, registry, golden.hiddenSeed, startYear)
            val lifecycle = TurnDaemonLifecycle(world, handler) { gid -> reservedRepo.readReserved(WorldId(1), gid, 0) }
            val runService = TurnRunService(
                world = world,
                commandStream = RedisCommandStream(template, profile, WorldId(1)),
                lifecycle = lifecycle,
                handler = handler,
                flushExecutor = flushExecutor,
                realtimePublisher = RealtimePublisher(template, profile, WorldId(1)),
                // Single-shot tick: the reserved ACTION lives in the general_turn ring (read per-general
                // by the lifecycle), and the reserve poke was published BEFORE this stream was built (so
                // it is already at the resolved tail cursor). A finite block drains the control stream
                // briefly then proceeds — `commandBlockMs = 0` (XREAD BLOCK 0 = block forever) would
                // exceed Lettuce's default command timeout and raise RedisCommandTimeoutException, since
                // no NEW command arrives in this single tick. (Continuous-daemon block semantics: P-later.)
                commandBlockMs = 250,
            )

            // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
            // 시각은 due가 아니다. production은 nextRunTime()=lastTurnTime+tick으로 호출하므로 t0보다 미래
            // 시각을 넘겨 골든 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
            val result = runService.runTick(t0.plusSeconds(1))

            // the reserved action resolved (NOT the 휴식 fallback); exactly one general drained + flushed
            assertEquals(1, result.handled.size, "exactly one due general drained in one pass")
            val handled = result.handled.single()
            assertFalse(handled.fellBack, "AVAILABLE general resolves che_상업투자, not the rest fallback")
            assertEquals(action, handled.definition.key)
            assertEquals(1, result.flushedGenerals)
            assertEquals(1, result.flushedCities)
            assertEquals(1, result.flushedLogs)

            // === STEP 5: flushed rows byte-match the golden DB dump ===============================
            assertGeneralRowMatchesGolden()
            assertCityRowMatchesGolden()
            assertLogEntryMatchesGolden()
            assertReservedExecutionResultCorrelated(reserveResult.requestId)

            // === STEP 6: ONLY general + city + log_entry changed (TruncateContract) ===============
            assertNoSpuriousWrites()

            // === STEP 4 (assert): a turnCompleted reached the channel AND the SSE relay forwarded it =
            assertTrue(latch.await(5, TimeUnit.SECONDS), "turnCompleted delivered on the realtime channel")
            val decoded = WireJson.decodeFromString(RealtimeEvent.serializer(), channelMsg.get()!!)
            assertTrue(decoded is RealtimeEvent.TurnCompleted, "the published event is turnCompleted")
            // at = 이번 틱의 runTime(TurnRunService atIso=runTime.toString()) — strict-< 교정으로 runTick을
            // t0.plusSeconds(1)로 호출하므로 at도 그 값이다(PHP 골든이 아니라 데몬이 echo하는 runTime).
            assertEquals(t0.plusSeconds(1).toString(), (decoded as RealtimeEvent.TurnCompleted).at)
            assertEquals(t0.toString(), decoded.lastTurnTime, "lastTurnTime = the pre-tick world clock (t0)")
            // the SAME turnCompleted JSON traversed the REAL SSE relay → emitter
            assertEquals(1, sseEvents.size, "exactly one event reached the SSE emitter")
            assertEquals(channelMsg.get(), sseEvents.single(), "the SSE relay forwarded the turnCompleted byte-for-byte")
        } finally {
            container.stop()
            template.delete(streamKeys.commandStream)
        }
    }

    @KTest
    fun `v2 API database precheck and daemon snapshot agree for recruit and transport`() {
        seedV2ParityState(crew = 2000, gold = 100_000)
        val api = V2CommandPrecheckService(buildRealPrecheckStateFactory(), jdbc, GameApiProcessWorld(1))

        val recruitArgs = V2GarrisonRecruitArgs(cityId = 1, amount = 100)
        val recruitAvailable = V2CommandAvailability.Available(V2CommandRegistry.garrisonRecruitSchema, recruitArgs)
        val apiRecruitAllow = jpaTx.execute { api.precheck(10, recruitAvailable) }
        val daemonRecruitAllow = V2GarrisonRecruitHandler(
            buildV2ParityWorld(crew = 2000), ChangeRecorder(), V2CityLedgerStore(jdbc),
        ).handle(CityGarrisonRecruit(generalId = 10, cityId = 1, amount = 100))
        assertEquals(recruitAvailable, apiRecruitAllow)
        assertTrue((daemonRecruitAllow as CommandLifecycleResult).ok)

        jdbc.update(
            "UPDATE v2_city_ledger SET gold = 0 WHERE world_id = 1 AND city_id = 1",
            MapSqlParameterSource(),
        )
        val apiRecruitDeny = jpaTx.execute { api.precheck(10, recruitAvailable) }
        val daemonRecruitDeny = V2GarrisonRecruitHandler(
            buildV2ParityWorld(crew = 2000), ChangeRecorder(), V2CityLedgerStore(jdbc),
        ).handle(CityGarrisonRecruit(generalId = 10, cityId = 1, amount = 100)) as CommandLifecycleResult
        assertSameV2Denial(apiRecruitDeny, daemonRecruitDeny)

        jdbc.update(
            "UPDATE v2_city_ledger SET gold = 100000 WHERE world_id = 1 AND city_id = 1",
            MapSqlParameterSource(),
        )
        val transportArgs = V2CityTransportArgs(1, 9, gold = 100, rice = 0, garrison = 0, routeRevision = 7)
        val transportAvailable = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, transportArgs)
        val apiTransportAllow = jpaTx.execute { api.precheck(10, transportAvailable) }
        val daemonTransportAllow = V2CityTransportHandler(
            buildV2ParityWorld(crew = 2000), ChangeRecorder(), V2CityLedgerStore(jdbc),
        ).handle(
            CityTransport(
                generalId = 10, fromCityId = 1, toCityId = 9,
                gold = 100, rice = 0, garrison = 0, routeRevision = 7,
            ),
        )
        assertEquals(transportAvailable, apiTransportAllow)
        assertTrue((daemonTransportAllow as CommandLifecycleResult).ok)

        jdbc.update("UPDATE general SET crew = 1999 WHERE world_id = 1 AND id = 10", MapSqlParameterSource())
        emf.cache.evictAll()
        val apiTransportDeny = jpaTx.execute { api.precheck(10, transportAvailable) }
        val daemonTransportDeny = V2CityTransportHandler(
            buildV2ParityWorld(crew = 1999), ChangeRecorder(), V2CityLedgerStore(jdbc),
        ).handle(
            CityTransport(
                generalId = 10, fromCityId = 1, toCityId = 9,
                gold = 100, rice = 0, garrison = 0, routeRevision = 7,
            ),
        ) as CommandLifecycleResult
        assertSameV2Denial(apiTransportDeny, daemonTransportDeny)
    }

    @KTest
    fun `v2 recruit denial matrix keeps real API state and daemon snapshot in parity`() {
        val cases = listOf(
            RecruitDenialCase("general missing", V2GarrisonRecruitArgs(1, 100), V2ParityState(generalPresent = false), "GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다."),
            RecruitDenialCase("city missing", V2GarrisonRecruitArgs(99, 100), V2ParityState(), "CITY_NOT_FOUND", "도시를 찾을 수 없습니다."),
            RecruitDenialCase("actor city mismatch", V2GarrisonRecruitArgs(9, 100), V2ParityState(), "ACTOR_CITY_MISMATCH", "다른 도시의 병사를 보충할 수 없습니다."),
            RecruitDenialCase("city authority", V2GarrisonRecruitArgs(1, 100), V2ParityState(city1NationId = 2), "CITY_AUTHORITY_DENIED", "자국 도시가 아닙니다."),
            RecruitDenialCase("minimum amount", V2GarrisonRecruitArgs(1, 99), V2ParityState(), "RECRUIT_AMOUNT_TOO_SMALL", "최소 100명부터 보충할 수 있습니다."),
            RecruitDenialCase("leadership limit", V2GarrisonRecruitArgs(1, 8_001), V2ParityState(), "RECRUIT_LEADERSHIP_LIMIT", "통솔로 보충할 수 있는 한도를 넘었습니다."),
            RecruitDenialCase(
                "population", V2GarrisonRecruitArgs(1, 100),
                V2ParityState(city1Population = GameConst.minAvailableRecruitPop + 99),
                "CITY_POPULATION_INSUFFICIENT", "주민이 부족합니다.",
            ),
            RecruitDenialCase("gold", V2GarrisonRecruitArgs(1, 100), V2ParityState(ledgerGold = 0), "CITY_GOLD_INSUFFICIENT", "도시의 금이 부족합니다."),
        )

        cases.forEach { case ->
            resetDatabase()
            seedV2ParityState(case.state)
            val available = V2CommandAvailability.Available(V2CommandRegistry.garrisonRecruitSchema, case.args)
            val api = V2CommandPrecheckService(buildRealPrecheckStateFactory(), jdbc, GameApiProcessWorld(1))
            val apiResult = jpaTx.execute { api.precheck(10, available) }
            val daemonResult = V2GarrisonRecruitHandler(
                buildV2ParityWorld(case.state), ChangeRecorder(), V2CityLedgerStore(jdbc),
            ).handle(CityGarrisonRecruit(generalId = 10, cityId = case.args.cityId, amount = case.args.amount))

            assertV2DenialParity(case.name, case.code, case.reason, apiResult, daemonResult)
        }
    }

    @KTest
    fun `v2 transport denial matrix keeps real API state and daemon snapshot in parity`() {
        val base = V2CityTransportArgs(1, 9, gold = 100, rice = 0, garrison = 0, routeRevision = null)
        val cases = listOf(
            TransportDenialCase("general missing", base, V2ParityState(generalPresent = false), "GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다."),
            TransportDenialCase("source missing", base.copy(fromCityId = 99, toCityId = 1), V2ParityState(), "FROM_CITY_NOT_FOUND", "출발 도시를 찾을 수 없습니다."),
            TransportDenialCase("target missing", base.copy(toCityId = 99), V2ParityState(), "TO_CITY_NOT_FOUND", "도착 도시를 찾을 수 없습니다."),
            TransportDenialCase("actor city mismatch", base.copy(fromCityId = 9, toCityId = 1), V2ParityState(), "ACTOR_CITY_MISMATCH", "장수가 있는 도시에서만 수송할 수 있습니다."),
            TransportDenialCase("same city", base.copy(toCityId = 1), V2ParityState(), "SAME_CITY", "같은 도시로는 수송할 수 없습니다."),
            TransportDenialCase("city authority", base, V2ParityState(city9NationId = 2), "CITY_AUTHORITY_DENIED", "자국 도시끼리만 수송할 수 있습니다."),
            TransportDenialCase("negative amount", base.copy(gold = -1), V2ParityState(), "TRANSPORT_AMOUNT_NEGATIVE", "수송량은 음수일 수 없습니다."),
            TransportDenialCase("empty amount", base.copy(gold = 0), V2ParityState(), "TRANSPORT_AMOUNT_EMPTY", "수송할 자원을 지정해야 합니다."),
            TransportDenialCase("route", base, V2ParityState(mapName = "not-a-map"), "ROUTE_NOT_ADJACENT", "인접한 도시로만 수송할 수 있습니다."),
            TransportDenialCase("escort", base, V2ParityState(crew = 1_999), "ESCORT_INSUFFICIENT", "수송에는 병사 2000명이 필요합니다."),
            TransportDenialCase("gold limit", base.copy(gold = 50_001), V2ParityState(), "TRANSPORT_GOLD_LIMIT", "금은 한 번에 50000까지 수송할 수 있습니다."),
            TransportDenialCase("rice limit", base.copy(gold = 0, rice = 50_001), V2ParityState(), "TRANSPORT_RICE_LIMIT", "병량은 한 번에 50000까지 수송할 수 있습니다."),
            TransportDenialCase("garrison limit", base.copy(gold = 0, garrison = 50_001), V2ParityState(), "TRANSPORT_GARRISON_LIMIT", "도시병사는 한 번에 50000까지 수송할 수 있습니다."),
            TransportDenialCase("gold resource", base, V2ParityState(ledgerGold = 0), "CITY_GOLD_INSUFFICIENT", "도시의 금이 부족합니다."),
            TransportDenialCase("rice resource", base.copy(gold = 0, rice = 100), V2ParityState(ledgerRice = 0), "CITY_RICE_INSUFFICIENT", "도시의 병량이 부족합니다."),
            TransportDenialCase("garrison resource", base.copy(gold = 0, garrison = 100), V2ParityState(ledgerGarrison = 0), "CITY_GARRISON_INSUFFICIENT", "도시의 병사가 부족합니다."),
        )

        cases.forEach { case ->
            resetDatabase()
            seedV2ParityState(case.state)
            val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, case.args)
            val api = V2CommandPrecheckService(buildRealPrecheckStateFactory(), jdbc, GameApiProcessWorld(1))
            val apiResult = jpaTx.execute { api.precheck(10, available) }
            val daemonResult = V2CityTransportHandler(
                buildV2ParityWorld(case.state), ChangeRecorder(), V2CityLedgerStore(jdbc),
            ).handle(
                CityTransport(
                    generalId = 10, fromCityId = case.args.fromCityId, toCityId = case.args.toCityId,
                    gold = case.args.gold, rice = case.args.rice, garrison = case.args.garrison,
                    routeRevision = case.args.routeRevision,
                ),
            )

            assertV2DenialParity(case.name, case.code, case.reason, apiResult, daemonResult)
        }
    }

    @KTest
    fun `v2 API transport precheck denies when active map is missing`() {
        assertTransportMapDenial("""{"startYear":190}""")
    }

    @KTest
    fun `v2 API transport precheck denies when active map is invalid`() {
        assertTransportMapDenial("""{"startYear":190,"mapName":"not-a-map"}""")
    }

    // === golden byte-compares (STEP 5) ============================================================

    /** general gold/exp/ded byte-match the golden + `meta` jsonb matches (content AND key order!). */
    private fun assertGeneralRowMatchesGolden() {
        val g = golden.general
        val row = jdbc.queryForMap(
            "SELECT gold, experience, dedication, meta::text AS meta FROM general WHERE id = :id",
            MapSqlParameterSource("id", generalId),
        )
        assertEquals(g.gold, intOf(row["gold"]), "general.gold byte-match golden")
        assertEquals(g.experience, intOf(row["experience"]), "general.experience byte-match golden (float→int round)")
        assertEquals(g.dedication, intOf(row["dedication"]), "general.dedication byte-match golden (float→int round)")

        // The `meta` column is jsonb — Postgres re-renders it on read (its own whitespace + key
        // normalization), so the column TEXT is the wrong oracle (JdbcFlushExecutorIT precedent).
        // Decode the stored jsonb into the insertion-ordered LinkedHashMap and assert BOTH the
        // logical content AND the KEY ORDER, then re-encode through the PHP-faithful MetaJson writer
        // (the row mapper's byte oracle) and byte-compare against the expected golden jsonb string.
        // opensamguk 스키마는 PHP의 killturn 전용 컬럼/aux(max_domestic_critical)를 모두 meta jsonb에 접는다.
        // strict-< 교정 후 per-general 꼬리가 실제로 돌면(applyKillturnDecrement→updateTurnTime, PHP
        // :153-165/:170-230) meta에 killturn(감소)·lived_month(+1)가 추가된다. 이는 골든 DB(che-golden-db.json)
        // 76번 행의 `killturn:105`(= BEFORE 106 - 1) AFTER 상태와 정확히 일치한다 — 액션 aux 3키(explevel/
        // intel_exp/max_domestic_critical)는 불변이고, 꼬리 2키(killturn·lived_month)가 PHP 캡처대로 추가된 것.
        val storedMeta = MetaJson.decode(stringOf(row["meta"]))
        assertEquals(
            linkedMapOf<String, Any?>(
                "myset" to golden.myset,
                "dedlevel" to golden.dedlevel,
                "explevel" to g.explevel,
                "killturn" to 105,
                "intel_exp" to g.intelExp,
                "lived_month" to 1, // updateTurnTime lived_month+1 (PHP :278), BEFORE 미설정(0) → 1.
                "max_domestic_critical" to g.maxDomesticCritical,
            ) as Map<String, Any?>,
            storedMeta as Map<String, Any?>,
            "general.meta content byte-match golden (액션 aux 3키 + 꼬리 killturn/lived_month — PHP 캡처 일치)",
        )
        // meta KEY ORDER: 이 키들은 PHP에선 전용 컬럼(killturn/explevel/intel_exp) + aux(max_domestic_critical)라
        // PHP jsonb 골든 순서가 없다 — opensamguk가 slice-meta jsonb로 접은 내부 직렬화 순서다(결정적이면 OK,
        // 규율6=비결정 reorder 금지). 실제 결정적 순서 = 로직 액션 meta 재구성(explevel→killturn→intel_exp) +
        // 꼬리(updateTurnTime lived_month 추가) → max_domestic_critical 말미. 매 실행 동일(LinkedHashMap 연산 결정적).
        assertEquals(
            listOf("myset", "dedlevel", "explevel", "killturn", "intel_exp", "lived_month", "max_domestic_critical"),
            storedMeta.keys.toList(),
            "general.meta KEY ORDER 결정적 (PHP는 컬럼/aux라 jsonb 순서 무관 — opensamguk slice-meta 내부 직렬화 결정성)",
        )
        // opensamguk slice-meta jsonb 정확 byte 문자열(결정적 순서, 51.5 not 51, killturn 105, lived_month 1).
        assertEquals(
            """{"myset":${golden.myset},"dedlevel":${golden.dedlevel},"explevel":${g.explevel},"killturn":105,"intel_exp":${g.intelExp},"lived_month":1,"max_domestic_critical":${fmt(g.maxDomesticCritical)}}""",
            MetaJson.encode(storedMeta),
            "general.meta jsonb byte-string (compact, 결정적 key order, killturn 105, lived_month 1)",
        )
    }

    /** city commerce/agriculture + the *_max + trust byte-match the golden. */
    private fun assertCityRowMatchesGolden() {
        val c = golden.city
        val row = jdbc.queryForMap(
            "SELECT comm, agri, comm_max, agri_max, trust FROM city WHERE id = :id",
            MapSqlParameterSource("id", cityId),
        )
        assertEquals(c.comm, intOf(row["comm"]), "city.comm byte-match golden (developed stat moved)")
        assertEquals(c.agri, intOf(row["agri"]), "city.agri byte-match golden (unchanged by 상업투자)")
        assertEquals(c.commMax, intOf(row["comm_max"]), "city.comm_max byte-match golden")
        assertEquals(c.agriMax, intOf(row["agri_max"]), "city.agri_max byte-match golden")
        assertEquals(c.trust.toInt(), intOf(row["trust"]), "city.trust byte-match golden")
    }

    private fun assertLogEntryMatchesGolden() {
        val count = jdbc.queryForObject(
            "SELECT count(*) FROM log_entry", MapSqlParameterSource(), Int::class.java,
        )
        assertEquals(1, count, "exactly one action log_entry row written")
        val row = jdbc.queryForMap(
            "SELECT scope::text AS scope, category::text AS category, text, year, month, phase, general_id, nation_id " +
                "FROM log_entry",
            MapSqlParameterSource(),
        )
        assertEquals("GENERAL", row["scope"])
        assertEquals("ACTION", row["category"])
        assertEquals(generalId, intOf(row["general_id"]))
        assertEquals(nationId, intOf(row["nation_id"]))
        assertEquals(year, intOf(row["year"]))
        assertEquals(month, intOf(row["month"]))
        val phase = intOf(row["phase"])
        assertEquals(1, phase, "log_entry.phase stores the current 삼모 순")
        assertEquals(
            golden.logText,
            row["text"],
            "log_entry.text byte-matches the PHP raw log",
        )
    }

    private fun assertReservedExecutionResultCorrelated(requestId: String) {
        val rows = jdbc.queryForList(
            """
            SELECT result_seq, result_type, ok, result_payload::text AS payload
              FROM command_result
             WHERE world_id = 1 AND request_id = :request_id
             ORDER BY result_seq
            """.trimIndent(),
            MapSqlParameterSource("request_id", requestId),
        )
        assertEquals(2, rows.size, "reservation request has admission and execution lifecycle rows")
        assertEquals(listOf(1, 2), rows.map { intOf(it["result_seq"]) })
        assertEquals(listOf("reservationAccepted", "executionApplied"), rows.map { it["result_type"] })
        assertEquals(listOf(true, true), rows.map { it["ok"] })
        assertTrue(rows[1]["payload"].toString().contains("\"requestId\": \"$requestId\""))
        assertTrue(rows[1]["payload"].toString().contains("\"type\": \"executionApplied\""))
        val outboxIds = jdbc.queryForList(
            """
            SELECT event_id
              FROM command_outbox
             WHERE world_id = 1 AND request_id = :request_id
             ORDER BY event_id
            """.trimIndent(),
            MapSqlParameterSource("request_id", requestId),
            String::class.java,
        )
        assertEquals(
            listOf("command-result:1:$requestId:1", "command-result:1:$requestId:2"),
            outboxIds,
        )
    }

    /** TruncateContract: ONLY general + city + log_entry changed; the rest stay empty. */
    private fun assertNoSpuriousWrites() {
        // Tables the slice must NOT write (inheritance bump is a P6 seam; no nation/troop/diplomacy/
        // rank/hall write on a che action). world_state + general + city + general_turn + log_entry
        // are the legitimately-touched rows.
        for (table in listOf(
            "troop", "diplomacy", "rank_data",
            "inheritance_point", "inheritance_log", "inheritance_result", "hall",
            "ng_old_nations", "nation_turn",
        )) {
            val n = jdbc.queryForObject(
                "SELECT count(*) FROM $table", MapSqlParameterSource(), Int::class.java,
            )
            assertEquals(0, n, "no spurious write to $table (TruncateContract)")
        }
        // `nation` is a SEEDED read-only precondition (NotWanderingNation/OccupiedCity read it), NOT a
        // slice-written table — so it is legitimately present. The TruncateContract here means the
        // flush must not MUTATE it: assert the seeded row is unchanged (still exactly one, level 2).
        assertEquals(1, intOf(jdbc.queryForObject("SELECT count(*) FROM nation", MapSqlParameterSource(), Int::class.java)), "nation not written (seeded precondition row unchanged)")
        assertEquals(2, intOf(jdbc.queryForObject("SELECT level FROM nation WHERE id = :id", MapSqlParameterSource("id", nationId), Int::class.java)), "seeded nation row unmutated by the slice flush")
        // exactly the two mutated entity rows exist, and exactly one general_turn reservation.
        assertEquals(1, intOf(jdbc.queryForObject("SELECT count(*) FROM general", MapSqlParameterSource(), Int::class.java)))
        assertEquals(1, intOf(jdbc.queryForObject("SELECT count(*) FROM city", MapSqlParameterSource(), Int::class.java)))
        assertEquals(1, intOf(jdbc.queryForObject("SELECT count(*) FROM general_turn", MapSqlParameterSource(), Int::class.java)))
    }

    // === seeds ====================================================================================

    /** Seed the golden BEFORE state into the DB (the rows the precheck reads + the flush UPDATEs). */
    private fun seedDbBeforeState() {
        val b = golden.before
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config) " +
                "VALUES (1, 'scenario_2', :y, :m, :t, CAST(:cfg AS jsonb))",
            MapSqlParameterSource()
                .addValue("y", year).addValue("m", month).addValue("t", tickSeconds)
                .addValue("cfg", """{"startYear":$startYear,"mapName":"che"}"""),
        )
        // nation 2 (the general's owning nation) — a precondition the constraints READ
        // (NotWanderingNation needs level != 0; OccupiedCity needs city.nationId == general.nationId).
        // The golden DB dump does NOT include nation (the slice never WRITES it); it is the seeded
        // world. level 2 matches the in-memory daemon world's Nation(level=2). The flush must NOT
        // touch this row (asserted by assertNoSpuriousWrites).
        jdbc.update(
            "INSERT INTO nation (world_id, id, name, color, capital_city_id, level, type_code) " +
                "VALUES (1, :id, 'n2', '#000', 99, 2, 'None')",
            MapSqlParameterSource("id", nationId),
        )
        // general no.76 BEFORE state (golden DB stats: leadership 31 / strength 68 / intel 49,
        // nation 2, city 1, officer_level 1; intel_exp/explevel/max_domestic_critical in meta).
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, meta)
            VALUES
                (1, :id, 'ⓝ엄정', :nation, :city, 31, 68, 49, 0,
                 :exp, :ded, 1, :gold, 1000, :tt,
                 CAST(:meta AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", generalId).addValue("nation", nationId).addValue("city", cityId)
                .addValue("exp", b.experience).addValue("ded", b.dedication).addValue("gold", b.gold)
                .addValue("tt", java.sql.Timestamp.from(t0))
                .addValue("meta", """{"myset":${golden.myset - GameConst.incDefSettingChange},"explevel":${b.explevel},"dedlevel":${golden.dedlevel},"intel_exp":${b.intelExp},"max_domestic_critical":${fmt(b.maxDomesticCritical)}}"""),
        )
        // city 1 BEFORE state (golden DB: level 8, nation 2, supply 1, front 0, trust 80).
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (1, :id, '업', 8, :nation, 1, 0, 434350, 620500,
                 :agri, :agriMax, :comm, :commMax, 7000, 10000, :trust, 100, 8190, 11700,
                 8540, 12200, 1, CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", cityId).addValue("nation", nationId)
                .addValue("agri", b.agri).addValue("agriMax", b.agriMax)
                .addValue("comm", b.comm).addValue("commMax", b.commMax)
                .addValue("trust", b.trust.toInt()),
        )
    }

    /** The in-memory daemon world mirroring the seeded BEFORE state (the daemon source of truth). */
    private fun buildWorldBeforeState(): InMemoryTurnWorld {
        val b = golden.before
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1, currentYear = year, currentMonth = month, tickSeconds = tickSeconds, lastTurnTime = t0,
                    config = linkedMapOf("mapName" to "che"),
                ),
                generals = listOf(
                    TurnGeneral(
                        id = generalId, name = "ⓝ엄정", nationId = nationId, cityId = cityId, troopId = 0,
                        stats = GeneralStats(leadership = 31, strength = 68, intelligence = 49),
                        experience = b.experience, dedication = b.dedication, officerLevel = 1,
                        // 골든 PHP 캡처(che-golden-db.json) 76번 장수는 `npc:2`(NPC), AFTER `killturn:105`.
                        // strict-< 교정 후 drain 꼬리(applyKillturnDecrement, TurnExecutionHelper.php:153-165)가
                        // NPC(npcState>=2) 분기로 killturn을 -1 하므로 BEFORE killturn=106 → AFTER 105(골든 일치).
                        // killturn 미설정(0)이면 tail의 killturn<=0 kill 게이트(:185)가 장수를 삭제해 flush가 비어진다.
                        gold = b.gold, rice = 1000, injury = 0, turnTime = t0, npcState = 2,
                        meta = linkedMapOf(
                            "myset" to golden.myset - GameConst.incDefSettingChange,
                            "explevel" to b.explevel,
                            "dedlevel" to golden.dedlevel,
                            "intel_exp" to b.intelExp,
                            "max_domestic_critical" to b.maxDomesticCritical,
                            "killturn" to 106,
                        ),
                    ),
                ),
                cities = listOf(
                    City(
                        id = cityId, name = "업", nationId = nationId, level = 8,
                        agriculture = b.agri, agricultureMax = b.agriMax,
                        commerce = b.comm, commerceMax = b.commMax,
                        supplyState = 1, frontState = 0,
                        // carry the seeded develop/defense columns so the widened step-7 city UPDATE
                        // (FF2) round-trips them faithfully instead of zeroing them.
                        population = 434350, populationMax = 620500,
                        security = 7000, securityMax = 10000,
                        defence = 8190, defenceMax = 11700,
                        wall = 8540, wallMax = 12200,
                        trade = 100, region = 1,
                        meta = linkedMapOf("trust" to b.trust),
                    ),
                ),
                nations = listOf(Nation(id = nationId, name = "n2", color = "#000", level = 2, capitalCityId = 99)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1, currentYear = year, currentMonth = month, tickSeconds = tickSeconds, lastTurnTime = t0,
                )).id),
            ),
        )
    }

    // === REAL JPA read path bootstrap =============================================================

    private data class V2ParityState(
        val generalPresent: Boolean = true,
        val generalCityId: Int = 1,
        val generalNationId: Int = 1,
        val leadership: Int = 80,
        val crew: Int = 2_000,
        val city1NationId: Int = 1,
        val city9NationId: Int = 1,
        val city1Population: Int = 50_000,
        val mapName: String? = "che",
        val ledgerGold: Long = 100_000,
        val ledgerRice: Long = 100_000,
        val ledgerGarrison: Int = 100_000,
    )

    private data class RecruitDenialCase(
        val name: String,
        val args: V2GarrisonRecruitArgs,
        val state: V2ParityState,
        val code: String,
        val reason: String,
    )

    private data class TransportDenialCase(
        val name: String,
        val args: V2CityTransportArgs,
        val state: V2ParityState,
        val code: String,
        val reason: String,
    )

    /** Build the REAL game-api precheck service over the REAL JPA read repos (Hibernate / Testcontainers). */
    private fun seedV2ParityState(crew: Int, gold: Long) =
        seedV2ParityState(V2ParityState(crew = crew, ledgerGold = gold))

    private fun seedV2ParityState(state: V2ParityState) {
        val config = state.mapName?.let { """{"startYear":190,"mapName":"$it"}""" }
            ?: """{"startYear":190}"""
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config) " +
                "VALUES (1, 'scenario_2', 200, 3, 3600, CAST(:config AS jsonb))",
            MapSqlParameterSource("config", config),
        )
        jdbc.update(
            "INSERT INTO nation (world_id, id, name, color, capital_city_id, level, type_code) " +
                "VALUES (1, 1, '위', '#0000ff', 1, 7, 'che_명사'), " +
                "(1, 2, '촉', '#00ff00', 9, 7, 'che_명사')",
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (1, 1, '업', 8, :city1_nation, 1, 0, :city1_pop, 100000, 4000, 8000, 3000, 8000,
                 1000, 2000, 82, 100, 500, 1000, 800, 1500, 1, '{}'::jsonb),
                (1, 9, '남피', 8, :city9_nation, 1, 0, 50000, 100000, 4000, 8000, 3000, 8000,
                 1000, 2000, 82, 100, 500, 1000, 800, 1500, 1, '{}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource("city1_nation", state.city1NationId)
                .addValue("city9_nation", state.city9NationId)
                .addValue("city1_pop", state.city1Population),
        )
        if (state.generalPresent) {
            jdbc.update(
                """
                INSERT INTO general
                    (world_id, id, name, nation_id, city_id, leadership, strength, intel, injury,
                     experience, dedication, officer_level, gold, rice, crew, turn_time, meta)
                VALUES
                    (1, 10, '관우', :nation, :city, :leadership, 90, 70, 0, 0, 0, 12,
                     1000, 1000, :crew, :turn_time, '{}'::jsonb)
                """.trimIndent(),
                MapSqlParameterSource("nation", state.generalNationId)
                    .addValue("city", state.generalCityId)
                    .addValue("leadership", state.leadership)
                    .addValue("crew", state.crew)
                    .addValue("turn_time", java.sql.Timestamp.from(t0)),
            )
        }
        jdbc.update(
            "INSERT INTO v2_city_ledger (world_id, city_id, gold, rice, garrison) " +
                "VALUES (1, 1, :gold, :rice, :garrison), (1, 9, 0, 0, 0)",
            MapSqlParameterSource("gold", state.ledgerGold)
                .addValue("rice", state.ledgerRice)
                .addValue("garrison", state.ledgerGarrison),
        )
        emf.cache.evictAll()
    }

    private fun buildV2ParityWorld(crew: Int): InMemoryTurnWorld =
        buildV2ParityWorld(V2ParityState(crew = crew))

    private fun buildV2ParityWorld(state: V2ParityState): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600,
                lastTurnTime = t0, config = state.mapName?.let { mapOf("mapName" to it) }.orEmpty(),
            ),
            generals = if (state.generalPresent) listOf(
                TurnGeneral(
                    id = 10, name = "관우", nationId = state.generalNationId,
                    cityId = state.generalCityId, troopId = 0,
                    stats = GeneralStats(state.leadership, 90, 70), experience = 0, dedication = 0,
                    officerLevel = 12, gold = 1000, rice = 1000, crew = state.crew, turnTime = t0,
                ),
            ) else emptyList(),
            cities = listOf(
                City(
                    id = 1, name = "업", nationId = state.city1NationId, level = 8,
                    population = state.city1Population, meta = mapOf("trust" to 82.0),
                ),
                City(id = 9, name = "남피", nationId = state.city9NationId, level = 8, population = 50000, meta = mapOf("trust" to 82.0)),
            ),
            nations = listOf(
                Nation(id = 1, name = "위", color = "#0000ff", level = 7),
                Nation(id = 2, name = "촉", color = "#00ff00", level = 7),
            ),
            worldId = WorldId(1),
        ),
    )

    private fun assertTransportMapDenial(config: String) {
        seedV2ParityState(crew = 2000, gold = 100_000)
        jdbc.update(
            "UPDATE world_state SET config = CAST(:config AS jsonb) WHERE id = 1",
            MapSqlParameterSource("config", config),
        )
        emf.cache.evictAll()
        val api = V2CommandPrecheckService(buildRealPrecheckStateFactory(), jdbc, GameApiProcessWorld(1))
        val args = V2CityTransportArgs(1, 9, gold = 100, rice = 0, garrison = 0, routeRevision = null)
        val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, args)

        val result = jpaTx.execute { api.precheck(10, available) }

        val denied = assertIs<V2CommandAvailability.Blocked>(result)
        assertEquals("ROUTE_NOT_ADJACENT", denied.code)
        assertEquals("인접한 도시로만 수송할 수 있습니다.", denied.reason)
    }

    private fun assertSameV2Denial(api: V2CommandAvailability?, daemon: CommandLifecycleResult) {
        val blocked = assertIs<V2CommandAvailability.Blocked>(api)
        assertFalse(daemon.ok)
        assertEquals(blocked.code, daemon.code)
        assertEquals(blocked.reason, daemon.reason)
    }

    private fun assertV2DenialParity(
        name: String,
        code: String,
        reason: String,
        api: V2CommandAvailability?,
        daemon: opensamguk.common.wire.TurnDaemonCommandResult,
    ) {
        val blocked = assertIs<V2CommandAvailability.Blocked>(api, name)
        val terminal = assertIs<CommandLifecycleResult>(daemon, name)
        assertFalse(terminal.ok, name)
        assertEquals(code, blocked.code, name)
        assertEquals(reason, blocked.reason, name)
        assertEquals(blocked.code, terminal.code, name)
        assertEquals(blocked.reason, terminal.reason, name)
    }

    private fun buildRealPrecheckService(): CommandPrecheckService =
        CommandPrecheckService(buildRealPrecheckStateFactory(), CommandRegistry(GeneralActionPipeline()))

    private fun buildRealPrecheckStateFactory(): PrecheckStateViewFactory {
        val em = SharedEntityManagerCreator.createSharedEntityManager(emf)
        val factory = JpaRepositoryFactory(em)
        val processWorld = GameApiProcessWorld(1)
        val generals = GeneralReadRepository(
            factory.getRepository(GeneralReadRawRepository::class.java),
            processWorld,
        )
        val cities = CityReadRepository(
            factory.getRepository(CityReadRawRepository::class.java),
            processWorld,
        )
        val nations = NationReadRepository(
            factory.getRepository(NationReadRawRepository::class.java),
            processWorld,
        )
        val diplomacies = DiplomacyReadRepository(
            factory.getRepository(DiplomacyReadRawRepository::class.java),
            processWorld,
        )
        val worldStates = WorldStateReadRepository(
            factory.getRepository(WorldStateReadRawRepository::class.java),
            processWorld,
        )
        return PrecheckStateViewFactory(generals, cities, nations, diplomacies, worldStates)
    }

    private fun buildEntityManagerFactory(ds: DataSource): EntityManagerFactory {
        val bean = LocalContainerEntityManagerFactoryBean()
        bean.setDataSource(ds)
        bean.setPackagesToScan("opensamguk.gameapi.read")
        bean.persistenceUnitName = "game-api-read-e2e"
        bean.jpaVendorAdapter = HibernateJpaVendorAdapter().apply {
            setGenerateDdl(false)
            setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect")
        }
        bean.setJpaProperties(
            Properties().apply {
                setProperty("hibernate.hbm2ddl.auto", "validate")
                setProperty("hibernate.format_sql", "false")
            },
        )
        bean.afterPropertiesSet()
        return bean.`object`!!
    }

    /**
     * Register a RECORDING [SseEmitter] into the REAL [relay] so that when
     * [RealtimeRelayController.fanOut] sends the `turnCompleted` payload it lands in [sink] — proving
     * the realtime signal traverses the production SSE relay's send path to a connected client, not
     * just the pub/sub channel. The emitter is added through the relay's own `/events` registration
     * path; the recording subclass overrides `send(Object)` to capture the data the relay emits.
     */
    private fun registerRecordingEmitter(relay: RealtimeRelayController, sink: MutableList<String>) {
        val recording = object : SseEmitter(0L) {
            override fun send(event: SseEmitter.SseEventBuilder) {
                // RealtimeRelayController.fanOut builds `event().name("realtime").data(json)`. The
                // built data set carries the SSE framing text + the JSON payload; record the JSON.
                // (No super.send: there is no bound MVC response in this IT — we only assert the
                //  relay's send path was invoked with the turnCompleted payload.)
                for (entry in event.build()) {
                    val data = entry.data
                    if (data is String && data.startsWith("{")) sink.add(data)
                }
            }
        }
        // Inject the recording emitter into the relay's private emitter list via the public events()
        // path is not possible (it creates its own); the relay's emitters field is private, so reflect
        // it once (test-only) to register our recording emitter so the REAL fanOut writes to it.
        val field = RealtimeRelayController::class.java.getDeclaredField("emitters")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val emitters = field.get(relay) as MutableList<SseEmitter>
        emitters.add(recording)
    }

    // === golden fixture loading ===================================================================

    private fun loadGolden(): Golden {
        val text = javaClass.classLoader
            .getResourceAsStream("golden/p1/che-action-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val case = root["cases"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["case"]!!.jsonPrimitive.content == caseName }
        val before = parseState(case["before"]!!.jsonObject)
        val after = parseState(case["after"]!!.jsonObject)
        val logText = case["logLines"]!!.jsonArray.single().jsonPrimitive.content

        // The golden DB dump (PHP legacy schema) is the authoritative AFTER row oracle; cross-check it
        // agrees with the action-fixtures AFTER state so the byte-compare targets are self-consistent.
        val dbText = javaClass.classLoader
            .getResourceAsStream("golden/p1/che-golden-db.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val db = Json.parseToJsonElement(dbText).jsonObject
        val dbGeneral = db["general"]!!.jsonArray.single().jsonObject
        val dbCity = db["city"]!!.jsonArray.single().jsonObject
        val dbLog = db["log_entry"]!!.jsonArray.single().jsonObject
        require(dbGeneral["gold"]!!.jsonPrimitive.int == after.gold) { "golden DB gold disagrees with fixtures AFTER" }
        require(dbGeneral["experience"]!!.jsonPrimitive.int == after.experience) { "golden DB experience disagrees" }
        require(dbGeneral["dedication"]!!.jsonPrimitive.int == after.dedication) { "golden DB dedication disagrees" }
        require(dbGeneral["intel_exp"]!!.jsonPrimitive.int == after.intelExp) { "golden DB intel_exp disagrees" }
        require(dbCity["comm"]!!.jsonPrimitive.int == after.comm) { "golden DB comm disagrees" }
        require(dbCity["agri"]!!.jsonPrimitive.int == after.agri) { "golden DB agri disagrees" }
        require(dbLog["text"]!!.jsonPrimitive.content == logText) { "golden DB log text disagrees with fixtures" }

        return Golden(
            hiddenSeed = hiddenSeed,
            before = before,
            general = after,
            city = after,
            logText = logText,
            dedlevel = dbGeneral["dedlevel"]!!.jsonPrimitive.int,
            myset = dbGeneral["myset"]!!.jsonPrimitive.int,
        )
    }

    private fun parseState(o: kotlinx.serialization.json.JsonObject): State {
        val g = o["general"]!!.jsonObject
        val c = o["city"]!!.jsonObject
        return State(
            gold = g["gold"]!!.jsonPrimitive.int,
            experience = g["experience"]!!.jsonPrimitive.int,
            dedication = g["dedication"]!!.jsonPrimitive.int,
            intelExp = g["intel_exp"]!!.jsonPrimitive.int,
            explevel = g["explevel"]!!.jsonPrimitive.int,
            maxDomesticCritical = g["max_domestic_critical"]!!.jsonPrimitive.double,
            comm = c["comm"]!!.jsonPrimitive.int,
            agri = c["agri"]!!.jsonPrimitive.int,
            commMax = c["comm_max"]!!.jsonPrimitive.int,
            agriMax = c["agri_max"]!!.jsonPrimitive.int,
            trust = c["trust"]!!.jsonPrimitive.double,
        )
    }

    /** Format a Double the PHP-faithful way for the expected jsonb byte-string (51.5 → "51.5", 0.0 → "0"). */
    private fun fmt(d: Double): String = if (d == Math.floor(d) && d.isFinite()) d.toLong().toString() else d.toString()

    private fun intOf(v: Any?): Int = (v as Number).toInt()
    private fun stringOf(v: Any?): String? = v?.toString()

    private data class Golden(
        val hiddenSeed: String,
        val before: State,
        val general: State,
        val city: State,
        val logText: String,
        val dedlevel: Int,
        val myset: Int,
    )

    private data class State(
        val gold: Int, val experience: Int, val dedication: Int, val intelExp: Int, val explevel: Int,
        val maxDomesticCritical: Double, val comm: Int, val agri: Int, val commMax: Int, val agriMax: Int,
        val trust: Double,
    )
}
