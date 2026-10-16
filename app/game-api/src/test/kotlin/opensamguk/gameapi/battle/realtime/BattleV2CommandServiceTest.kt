package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.security.*
import opensamguk.infra.battle.realtime.*
import opensamguk.logic.battle.realtime.*
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

class BattleV2CommandServiceTest {
    private val mapper = ObjectMapper()
    private val codec = BattleV2WireCodec(mapper)
    private val world = WorldId(1)
    private val identity = BattleJoinIdentity("pep", world, "battle", 42, 1, 7,
        "ATTACKER", 2, 3, Instant.now().plusSeconds(60))
    private val own = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "701")
    private val placement = TacticalV2Deployment(0, "c".repeat(64),
        listOf(TacticalV2UnitSource(own, BattleSide.ATTACKER, 7, 7, 100, 0)),
        mapOf(own to TacticalV2Cell(0, 0)),
        mapOf(BattleSide.ATTACKER to setOf(TacticalV2Cell(0, 0), TacticalV2Cell(0, 1)),
            BattleSide.DEFENDER to emptySet()))
    private val store = mock(BattleSessionStore::class.java)
    private val frozen = mock(BattleV2FrozenPlacementCodec::class.java)
    private val service = BattleV2CommandService(store, frozen, codec)
    private val move = BattleV2DeploymentMoveWire(2, "DEPLOYMENT_MOVE", "id", own,
        TacticalV2Cell(0, 1), 2, 3, 0)
    private val ticket = FrozenBattleTicket(world, "battle", "{}", "a".repeat(64), "b".repeat(64),
        "c".repeat(64), "d".repeat(64), 1, 0, 0, Instant.now().plusSeconds(120),
        Instant.now().plusSeconds(300), listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)))

    private val placeholder = BattleCommandRecord(world, "battle", 1, "placeholder", "a".repeat(64),
        2, 3, 0, "ATTACKER", "{}")
    private fun anyRecord(): BattleCommandRecord = any(BattleCommandRecord::class.java) ?: placeholder

    private fun arrange() {
        `when`(store.ticket(world, "battle")).thenReturn(ticket)
        `when`(frozen.initialDeployment(ticket)).thenReturn(placement)
    }

    @Test
    fun `service admits exact intent and durable deployment without a v1 slot`() {
        arrange()
        `when`(store.admit(anyRecord())).thenReturn(CommandAdmission.Receipt(
            BattleCommandReceipt("id", BattleCommandVerdict.ACCEPTED, null, 0, null, null,
                3, deploymentRevisionAfter = 1)))
        val ack = mapper.readTree(service.move(identity, move))
        assertEquals("1", ack["receipt"]["deploymentRevisionAfter"].textValue())
        val record = ArgumentCaptor.forClass(BattleCommandRecord::class.java)
        verify(store).admit(record.capture() ?: placeholder)
        assertEquals(placement, record.value.deploymentMove?.initial)
        assertEquals(own, record.value.deploymentMove?.sourceKey)
        assertFalse(record.value.intentJson.contains("slot"))
        assertNotEquals(codec.intent(move), codec.intent(move.copy(expectedDeploymentRevision = 1)))
    }

    @Test
    fun `service maps conflict and rejects a mismatching authenticated participant before write`() {
        arrange()
        `when`(store.admit(anyRecord())).thenReturn(CommandAdmission.IdempotencyConflict)
        val ack = mapper.readTree(service.move(identity, move))
        assertEquals("IDEMPOTENCY_CONFLICT", ack["receipt"]["reasonCode"].textValue())
        clearInvocations(store)
        assertFails { service.move(identity.copy(generalId = 8), move) }
        verify(store, never()).admit(anyRecord())
    }

    private fun policy() = ServerAdmissionPolicy(ServerAdmissionSource {
        ServerAdmissionRead.Known(ServerAdmissionSnapshot("pep", ServerPublicationState.PUBLIC, 1),
            System.nanoTime(), Duration.ofSeconds(2).toNanos())
    })

    @Test
    fun `socket command is fenced by current account ownership and latest reservation`() {
        val tickets = mock(BattleJoinTicketService::class.java)
        val generals = mock(GeneralResolver::class.java)
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val sessions = BattleWebSocketSessions(tickets, generals, policy())
        val reservation = sessions.reserve(identity)
        val socket = mock(WebSocketSession::class.java)
        `when`(socket.isOpen).thenReturn(true)
        `when`(socket.attributes).thenReturn(mutableMapOf<String, Any>(
            BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
        assertTrue(sessions.attach(reservation, socket))
        var writes = 0
        val handler = BattleWebSocketHandler(sessions, { "snapshot" }, { _, _ -> writes++; "ack" })
        handler.handleMessage(socket, TextMessage("move"))
        assertEquals(1, writes)
        sessions.reserve(identity)
        handler.handleMessage(socket, TextMessage("move"))
        assertEquals(1, writes)
        verify(socket, atLeastOnce()).close(CloseStatus.GOING_AWAY)
        `when`(generals.resolveGeneralId(42L)).thenReturn(8)
        handler.handleMessage(socket, TextMessage("move"))
        assertEquals(1, writes)
        verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION)
    }

    @Test
    fun `malformed text and unsupported COMMAND close without invoking persistence`() {
        val tickets = mock(BattleJoinTicketService::class.java)
        val generals = mock(GeneralResolver::class.java)
        `when`(tickets.isCurrent(identity)).thenReturn(true)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        for (text in listOf("{}", "{\"schemaVersion\":1}", "{\"schemaVersion\":2,\"t\":\"COMMAND\"}")) {
            val sessions = BattleWebSocketSessions(tickets, generals, policy())
            val reservation = sessions.reserve(identity)
            val socket = mock(WebSocketSession::class.java)
            `when`(socket.isOpen).thenReturn(true)
            `when`(socket.attributes).thenReturn(mutableMapOf<String, Any>(
                BattleWebSocketSessions.RESERVATION_ATTRIBUTE to reservation))
            assertTrue(sessions.attach(reservation, socket))
            BattleWebSocketHandler(sessions, { "snapshot" }, { _, frame ->
                codec.deploymentMove(frame)
                error("must not write")
            }).handleMessage(socket, TextMessage(text))
            verify(socket, atLeastOnce()).close(CloseStatus.POLICY_VIOLATION)
            verify(socket, never()).sendMessage(any(TextMessage::class.java))
        }
    }
    @Test
    fun `real websocket codec PostgreSQL ACK roundtrip survives API reconstruction and reconnect`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(runCatching {
            org.testcontainers.DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false), "Docker unavailable — PostgreSQL roundtrip skipped")
        val postgres: org.testcontainers.containers.PostgreSQLContainer<*> =
            org.testcontainers.containers.PostgreSQLContainer("postgres:16-alpine")
        val contexts = mutableListOf<org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext>()
        val sockets = mutableListOf<WebSocketSession>()
        try {
            postgres.start()
            val ds = org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.jdbcUrl, postgres.username, postgres.password)
            org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
            val jdbc = org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds)
            jdbc.update("""INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
                VALUES (1, 'v2-roundtrip', 200, 1, 3600)""",
                org.springframework.jdbc.core.namedparam.MapSqlParameterSource())
            fun digest(text: String) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val catalog = WaryongBoardCatalogResource.load()
            val board = catalog.boards.first { it.landEligible && it.battlefield.kind == "FIELD" }
            val cells = (0..63).flatMap { row -> (0..63).map { col -> TacticalV2Cell(row, col) } }
                .filter { board.battlefield.at(it.row, it.col) in "PFMR" }.take(4)
            val ally = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "702")
            val enemy = TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, "801")
            val allowed = mapOf(BattleSide.ATTACKER to cells.take(3).toSet(),
                BattleSide.DEFENDER to setOf(cells[3]))
            val initial = TacticalV2Deployment(board.battlefield.id,
                digest(board.battlefield.rows.joinToString("")), listOf(
                    TacticalV2UnitSource(own, BattleSide.ATTACKER, 7, 7, 100, 2),
                    TacticalV2UnitSource(ally, BattleSide.ATTACKER, 9, 9, 80, 4),
                    TacticalV2UnitSource(enemy, BattleSide.DEFENDER, 8, 8, 90, 3)),
                mapOf(own to cells[0], ally to cells[1], enemy to cells[3]), allowed)
            val pin = BattleV2PlacementPin.from(initial)
            val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val join = now.plusSeconds(120)
            val deadline = now.plusSeconds(300)
            val rule = "b".repeat(64)
            val terrain = "d".repeat(64)
            val body = """{"schemaVersion":2,"worldId":1,"battleId":"roundtrip","kind":"ENCOUNTER","battlefieldId":${board.battlefield.id},"ruleSha256":"$rule","catalogSha256":"${catalog.catalogSha256}","terrainSha256":"$terrain","seed":17,"lockGeneration":4,"lockSetRevision":2,"joinDeadlineAt":"$join","deadlineAt":"$deadline","tacticalInput":{"schemaVersion":2,"placement":{"schemaVersion":2,"boardId":${pin.boardId},"terrainSha256":"${pin.terrainSha256}","sourceCount":${pin.sourceCount},"deploymentBase64":"${pin.deploymentBase64}","deploymentSha256":"${pin.deploymentSha256}"}}}"""
            val firstStore = JdbcBattleSessionStore(jdbc, ds)
            assertTrue(firstStore.create(FrozenBattleTicket(world, "roundtrip", body, digest(body),
                rule, catalog.catalogSha256, terrain, 17, 4, 2, join, deadline,
                listOf(FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)))))
            val head = assertNotNull(firstStore.claimEpoch(world, "roundtrip", "actor", 60_000))
            val realGenerals = mock(GeneralResolver::class.java)
            `when`(realGenerals.resolveGeneralId(42L)).thenReturn(7)

            fun connect(durableStore: BattleSessionStore): java.util.concurrent.BlockingQueue<String> {
                val ticketService = BattleJoinTicketService(durableStore, ByteArray(32) { 7 },
                    java.time.Clock.systemUTC(), "pep")
                val token = ticketService.issue(world, "roundtrip", 42, 7)
                val frozenCodec = BattleV2FrozenPlacementCodec(catalog, rule) { allowed }
                val wire = BattleV2WireCodec(ObjectMapper())
                val commands = BattleV2CommandService(durableStore, frozenCodec, wire)
                val publisher = BattleV2JoiningSnapshotPublisher(ticketService, realGenerals,
                    durableStore, frozenCodec, catalog, ObjectMapper())
                val publication = policy()
                val sessions = BattleWebSocketSessions(ticketService, realGenerals, publication)
                val handler = BattleWebSocketHandler(sessions, publisher::snapshot) { current, text ->
                    commands.move(current, wire.deploymentMove(text))
                }
                val admission = BattleWebSocketAdmission(ticketService,
                    opensamguk.gameapi.config.GameApiProcessWorld(1), realGenerals,
                    "http://localhost", sessions, publication)
                val context = org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext()
                contexts += context
                context.beanFactory.registerSingleton("roundTripHandler", handler)
                context.beanFactory.registerSingleton("roundTripAdmission", admission)
                context.register(BattleV2RoundTripWebSocketConfiguration::class.java)
                context.refresh()
                assertNotNull(context.servletContext?.getAttribute("jakarta.websocket.server.ServerContainer"),
                    "test Tomcat must initialize the real WebSocket upgrade container")
                val frames = java.util.concurrent.LinkedBlockingQueue<String>()
                val client = org.springframework.web.socket.client.standard.StandardWebSocketClient()
                val headers = org.springframework.web.socket.WebSocketHttpHeaders().apply {
                    origin = "http://localhost"
                    secWebSocketProtocol = listOf("battle.v1", token)
                }
                val socket = client.execute(object : org.springframework.web.socket.handler.TextWebSocketHandler() {
                    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                        frames.add(message.payload)
                    }
                }, headers, java.net.URI("ws://127.0.0.1:${context.webServer.port}/ws/battles/pep/1/roundtrip?lastSeenEventSeq=0"))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS)
                sockets += socket
                assertEquals("battle.v1", socket.acceptedProtocol)
                return frames
            }
            fun receive(frames: java.util.concurrent.BlockingQueue<String>): String =
                assertNotNull(frames.poll(20, java.util.concurrent.TimeUnit.SECONDS), "missing WebSocket frame")

            val first = connect(firstStore)
            val initialSnapshot = mapper.readTree(receive(first))
            assertEquals("0", initialSnapshot["deployment"]["revision"].textValue())
            assertEquals("701", initialSnapshot["ownUnits"][0]["sourceKey"]["sourceId"].textValue())
            assertEquals(100, initialSnapshot["ownUnits"][0]["troops"].intValue())
            assertNull(first.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS), "unsolicited frame on read-only JOINING socket")
            val moveFrame = mapper.writeValueAsString(linkedMapOf("schemaVersion" to 2,
                "t" to "DEPLOYMENT_MOVE", "clientCommandId" to "roundtrip-move",
                "sourceKey" to linkedMapOf("kind" to "RETINUE", "sourceId" to 701),
                "targetCell" to linkedMapOf("row" to cells[2].row, "col" to cells[2].col),
                "expectedEpoch" to head.sessionEpoch.toString(), "expectedAuthorityRevision" to "3",
                "expectedDeploymentRevision" to "0"))
            println("C3A_ROUNDTRIP_MOVE_FRAME=$moveFrame")
            sockets.last().sendMessage(TextMessage(moveFrame))
            val acceptedText = receive(first)
            val accepted = mapper.readTree(acceptedText)
            assertEquals("ACCEPTED", accepted["receipt"]["verdict"].textValue())
            assertEquals("1", accepted["receipt"]["deploymentRevisionAfter"].textValue())
            println("C3A_ROUNDTRIP_ACK_FRAME=$acceptedText")
            assertNull(first.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS), "only the requested ACK may follow a move")
            sockets.last().close(CloseStatus.NORMAL)
            contexts.last().close()

            // Recreate the HTTP/WS server, API/store/ticket/codec/publisher/session objects, retaining only DB facts.
            val reconstructed = JdbcBattleSessionStore(jdbc, ds)
            val reconnected = connect(reconstructed)
            val snapshotText = receive(reconnected)
            println("C3A_RECONNECT_SNAPSHOT_FRAME=$snapshotText")
            val snapshot = mapper.readTree(snapshotText)
            assertEquals(accepted["receipt"]["deploymentRevisionAfter"], snapshot["deployment"]["revision"])
            val expectedSnapshot = initialSnapshot.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            (expectedSnapshot["deployment"] as com.fasterxml.jackson.databind.node.ObjectNode).put("revision", "1")
            for (cell in listOf(expectedSnapshot["ownUnits"][0]["cell"], expectedSnapshot["deployment"]["ownPositions"][0]["cell"])) {
                (cell as com.fasterxml.jackson.databind.node.ObjectNode).put("row", cells[2].row).put("col", cells[2].col)
            }
            assertEquals(expectedSnapshot, snapshot, "reconnect changes only durable revision and owned cells")
            assertEquals(cells[2].row, snapshot["deployment"]["ownPositions"][0]["cell"]["row"].intValue())
            assertEquals(cells[2].col, snapshot["deployment"]["ownPositions"][0]["cell"]["col"].intValue())
            val textualMove = mapper.readTree(moveFrame).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            (textualMove["sourceKey"] as com.fasterxml.jackson.databind.node.ObjectNode).put("sourceId", "701")
            sockets.last().sendMessage(TextMessage(textualMove.toString()))
            val replayText = receive(reconnected)
            val replay = mapper.readTree(replayText)
            assertEquals(true, replay["replayed"].booleanValue())
            assertEquals(accepted["receipt"], replay["receipt"])
            assertNull(reconnected.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS), "only the requested ACK may follow a move")
            println("C3A_RECONNECT_REPLAY_ACK_FRAME=$replayText")
            val before = reconstructed.deployment(world, "roundtrip", initial)
            val foreign = mapper.readTree(moveFrame).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
                .put("clientCommandId", "foreign-move").put("expectedDeploymentRevision", "1")
            (foreign["sourceKey"] as com.fasterxml.jackson.databind.node.ObjectNode).put("sourceId", 702)
            sockets.last().sendMessage(TextMessage(foreign.toString()))
            assertEquals("UNAUTHORIZED", mapper.readTree(receive(reconnected))["receipt"]["reasonCode"].textValue())
            assertNull(reconnected.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS), "only the requested ACK may follow a move")
            assertEquals(before, reconstructed.deployment(world, "roundtrip", initial))
            assertEquals(head, reconstructed.head(world, "roundtrip"))
            assertTrue(reconstructed.eventsAfter(world, "roundtrip", 0).isEmpty())
        } finally {
            try { sockets.forEach { if (it.isOpen) it.close(CloseStatus.NORMAL) } }
            finally {
                try { contexts.asReversed().forEach { it.close() } }
                finally { postgres.stop() }
            }
        }
    }

}

/** Same isolated embedded-Tomcat pattern as ReservationSlotBoundaryIT; no product boot/security config changes. */
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@org.springframework.web.servlet.config.annotation.EnableWebMvc
@org.springframework.web.socket.config.annotation.EnableWebSocket
class BattleV2RoundTripWebSocketConfiguration(
    private val handler: BattleWebSocketHandler,
    private val admission: BattleWebSocketAdmission,
) : org.springframework.web.socket.config.annotation.WebSocketConfigurer {
    @org.springframework.context.annotation.Bean
    fun serverFactory() = org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory(0)
        .apply {
            address = java.net.InetAddress.getByName("127.0.0.1")
            // This manual factory does not receive Boot's WebSocket servlet customizer.
            addContextCustomizers({ context ->
                context.addServletContainerInitializer(org.apache.tomcat.websocket.server.WsSci(), null)
            })
        }

    @org.springframework.context.annotation.Bean
    fun dispatcherRegistration(context: org.springframework.web.context.WebApplicationContext) =
        org.springframework.boot.web.servlet.ServletRegistrationBean(
            org.springframework.web.servlet.DispatcherServlet(context), "/").apply { setLoadOnStartup(1) }

    override fun registerWebSocketHandlers(registry: org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/battles/*/*/*").addInterceptors(admission)
            .setAllowedOrigins("http://localhost")
    }
}
