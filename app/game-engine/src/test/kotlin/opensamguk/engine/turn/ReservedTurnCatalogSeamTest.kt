package opensamguk.engine.turn

import java.time.Instant
import kotlinx.serialization.json.*
import kotlin.test.*
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.input.*
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Pre-delivery handler contract only: not delivery evidence, catalog promotion or product QA. */
class ReservedTurnCatalogSeamTest {
    private val inputId = PoliticalInput.INDEPENDENCE
    private val reservation = ReservedTurn(inputId, "{}", requestId = "catalog-seam", reservationOwnerUserId = 42)

    private fun world() = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH,
            config = mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA")),
        worldId = WorldId(1),
        generals = listOf(TurnGeneral(id = 1, name = "actor", userId = "42", nationId = 1, cityId = 1,
            troopId = 0, stats = GeneralStats(60, 60, 60), experience = 0, dedication = 0,
            officerLevel = 12, npcState = 0, turnTime = Instant.EPOCH, meta = mapOf("lord" to true))),
        cities = listOf(City(1, "county", 1, 1)),
        nations = listOf(Nation(1, "nation", "#000000", capitalCityId = 1, chiefGeneralId = 1)),
        administrativeCountyIds = setOf(1),
        generalPositionSnapshot = GeneralPositionSnapshot("r1", "a".repeat(64), setOf("p1"), emptySet())
            .withState(GeneralPositionState("r1", "a".repeat(64), 1, StrategicNodeRef.LandProvince("p1"), 1)),
        cityLandProvinceById = mapOf(1 to "p1"),
    ))

    private fun contractCatalog(): InputCatalog {
        val production = InputCatalog.load()
        val source = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val root = Json.parseToJsonElement(source).jsonObject
        val rows = root.getValue("inputs").jsonArray.map { element ->
            val row = element.jsonObject
            if (row.getValue("inputId").jsonPrimitive.content != inputId) row
            else JsonObject(row + ("deliveryState" to JsonPrimitive("HANDLER_READY")))
        }
        val derived = InputCatalog.parse(JsonObject(root + ("inputs" to JsonArray(rows))).toString())
        val before = production.entries.associate { it.inputId to it.deliveryState }
        val after = derived.entries.associate { it.inputId to it.deliveryState }
        assertEquals(before.keys, after.keys)
        assertEquals(mapOf(inputId to (InputDeliveryState.PLANNED to InputDeliveryState.HANDLER_READY)),
            before.filter { (id, state) -> after[id] != state }.mapValues { (id, state) -> state to after.getValue(id) })
        // Kind, evidence, failure reasons and every other entry field must remain production-identical.
        assertEquals(production.entries.map { if (it.inputId == inputId)
            it.copy(deliveryState = InputDeliveryState.HANDLER_READY) else it }, derived.entries)
        return derived
    }

    @Test fun `default catalog still rejects independence without political mutation`() {
        val world = world()
        val people = world.listGenerals()
        val counties = world.listCities()
        val nations = world.listNations()
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "00", 200)
        val handled = handler.handle(1, reservation, 200, 1, "00:00")
        assertEquals("NOT_DELIVERED", assertIs<TurnOutcome.Rejected>(handled.inputOutcome).code)
        assertFalse(handled.fellBack)
        assertEquals(people, world.listGenerals())
        assertEquals(counties, world.listCities())
        assertEquals(nations, world.listNations())
        assertFalse(handler.recorder.isDirty)
    }

    @Test fun `independence only derived catalog passes registry and political delivery gates lazily once`() {
        val catalog = contractCatalog()
        var calls = 0
        val handler = ReservedTurnHandler(world(), CommandRegistry(GeneralActionPipeline()), "00", 200,
            inputCatalogSource = { calls++; catalog })
        assertEquals(0, calls, "construction must not load or validate the catalog")
        repeat(2) {
            val outcome = assertIs<TurnOutcome.Rejected>(handler.handle(1, reservation, 200, 1, "00:00").inputOutcome)
            assertEquals(PoliticalFailure.ALREADY_LORD.name, outcome.code)
            assertEquals(1, calls, "registration, registry and PoliticalHandler must reuse one lazy catalog")
        }
    }

    @Test fun `real reserved execution receipt retains production kind and political rejection code`() {
        val world = world()
        val catalog = contractCatalog()
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "00", 200,
            inputCatalogSource = { catalog })
        val lifecycle = TurnDaemonLifecycle(world, handler,
            pullGeneralTurnOf = { gid, selected -> handler.recorder.recordGeneralTurnPull(gid, expectedReservation = selected) },
            reservedActionOf = { reservation })
        val payloads = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(mock(NamedParameterJdbcTemplate::class.java), mock(TransactionTemplate::class.java)) {
            override fun flush(payload: FlushPayload) { payloads += payload }
        }
        val service = TurnRunService(world, mock(RedisCommandStream::class.java), lifecycle, handler,
            flush, mock(RealtimePublisher::class.java))
        val handled = service.runDueGeneralTurns(Instant.EPOCH.plusSeconds(1)).handled.single()
        val outcome = assertIs<TurnOutcome.Rejected>(handled.inputOutcome)
        assertEquals(PoliticalFailure.ALREADY_LORD.name, outcome.code)
        val row = payloads.single().commandResults.single()
        assertEquals(reservation.requestId, row.requestId)
        assertEquals("executionRejected", row.resultType)
        val envelope = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), row.envelopeJson)
        val receipt = (envelope.event as TurnDaemonEvent.CommandResult).result as CommandLifecycleResult
        val resolved = assertNotNull(receipt.inputResolved)
        assertFalse(receipt.ok)
        assertFalse(resolved.ok)
        assertEquals(inputId, resolved.inputId)
        assertEquals("GENERAL_ACTION", resolved.kind)
        assertEquals(outcome.code, receipt.code)
        assertEquals(outcome.code, resolved.reason)
    }
}
