package opensamguk.engine.invariance

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import opensamguk.engine.campaign.*
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.war.BattleJournal
import org.mockito.Mockito.mock
import kotlin.test.*

/** Test-only provisional evidence. No JDBC flush, product storage or public API. */
internal class G6TurnOrderMetricFixture {
    enum class Probe { NONE, DROP_HOOK }
    enum class Scenario { ENCOUNTER, NO_ENCOUNTER, UNAVAILABLE, DISBANDED, DEFENDER_NOT_DUE }
    data class Order(val generalId: Int, val ordinal: Int?, val absentReason: String?,
        val tieGroupSize: Int, val idRank: Int)
    data class Row(val worldId: Int, val year: Int, val month: Int, val phase: Int,
        val encounterId: String, val attacker: Order, val defender: Order,
        val outcome: String, val winners: List<Int>,
        val installedStratagemTriggered: String = "UNAVAILABLE",
        val responseStratagemTriggered: String = "UNAVAILABLE",
        val interceptPolicyTriggered: String = "UNAVAILABLE",
        val evadePolicyTriggered: String = "UNAVAILABLE")
    data class Report(val rows: List<Row>, val starts: List<Int>, val handled: List<Int>,
        val expectedOrder: List<Int>, val resolutions: List<String>, val bytes: String,
        val battleRecordPresent: Boolean)

    fun replay(label: String, scenario: Scenario = Scenario.ENCOUNTER,
        probe: Probe = if (System.getenv("OPENSAMGUK_G6_PROBE") == "DROP_HOOK") Probe.DROP_HOOK else Probe.NONE): Report {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val start = Instant.parse("0200-01-01T00:00:00Z")
        // Deliberately reverse insertion order: the lifecycle, not fixture order, must decide G1.
        val world = fixture.world(listOf(
            fixture.person(100, 2, route.startCity, userId = "142").copy(npcState = 0) to route.first,
            fixture.person(1, 1, route.startCity, userId = "42").copy(npcState = 0) to route.start),
            bugoks = listOf(fixture.unit(7, 1, 1000,
                crewTypeId = if (scenario == Scenario.DISBANDED) 9999 else 1100), fixture.unit(1100, 100, 100)))
        val recorder = ChangeRecorder()
        if (scenario != Scenario.NO_ENCOUNTER) {
            fixture.deploy(world, recorder, 1, listOf(7), route.destination)
            fixture.deploy(world, recorder, 100, listOf(1100), route.first)
            fixture.nextPhase(world)
            assertTrue(CorpsMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells).onTurn(1))
            assertNotNull(CorpsEncounter.read(world.getGeneralById(1)!!.meta, fixture.topology))
            if (scenario == Scenario.UNAVAILABLE) for (id in listOf(1, 100)) {
                val actor = world.getGeneralById(id)!!
                world.applyGeneralDirtyFree(actor.copy(meta = actor.meta - BattleJournal.META_KEY))
            }
            fixture.nextPhase(world)
        }
        if (scenario == Scenario.DEFENDER_NOT_DUE) {
            val defender = world.getGeneralById(100)!!
            world.applyGeneralDirtyFree(defender.copy(turnTime = start.plusSeconds(7200)))
        }
        val initial = world.listGenerals().associateBy { it.id }
        val expected = initial.values.filter { it.turnTime < start.plusSeconds(1) }
            .sortedWith(compareBy({ it.turnTime }, { it.id })).map { it.id }
        val starts = mutableListOf<Int>()
        val handled = mutableListOf<Int>()
        val observations = mutableListOf<BattleOutcomeObservation>()
        val resolutions = mutableListOf<String>()
        val observer = if (probe == Probe.DROP_HOOK) BattleOutcomeObserver.NONE
            else BattleOutcomeObserver { observations += it }
        val resolver = EncounterResolver(world, recorder, fixture.topology, fixture.metrics, fixture.cells,
            observations = observer)
        val handler = ReservedTurnHandler(world, registry = mock(), recorder = recorder,
            hiddenSeed = "g6-fixed-synthetic-seed", startYear = 200)
        val lifecycle = TurnDaemonLifecycle(world, handler,
            observeGeneralTurnStart = { starts += it }, observeHandledTurn = { handled += it.generalId },
            movementOf = { id, _, _ ->
                resolutions += when (resolver.resolvePending(id)) {
                    is EncounterResolver.Resolution.Resolved -> "Resolved"
                    is EncounterResolver.Resolution.Unavailable -> "Unavailable"
                    is EncounterResolver.Resolution.Disbanded -> "Disbanded"
                    EncounterResolver.Resolution.NotPending -> "NotPending"
                    EncounterResolver.Resolution.NotAttacker -> "NotAttacker"
                }
            }, reservedActionOf = { CampaignWorldFixture.NO_INPUT })
        val result = lifecycle.runTick(start.plusSeconds(1))
        assertEquals(expected, starts, "G1 actual start observer order")
        assertEquals(starts, result.map { it.generalId }, "all observed turns completed")
        assertEquals(starts, handled, "handled observer order")
        fun order(id: Int): Order {
            val ties = initial.values.filter { it.turnTime == initial.getValue(id).turnTime }.map { it.id }.sorted()
            val ordinal = starts.indexOf(id).takeIf { it >= 0 }?.plus(1)
            return Order(id, ordinal, if (ordinal == null) "NO_PERSONAL_TURN_IN_PHASE" else null,
                ties.size, ties.indexOf(id) + 1)
        }
        // Join after the drain: a defender may have its turn after the attacking turn resolves.
        val rows = observations.map { o -> Row(o.worldId, o.resolvedYear, o.resolvedMonth, o.resolvedPhase,
            o.encounterId, order(1), order(100), o.outcome, o.winners) }
        val bytes = ObjectMapper().writeValueAsString(rows) + "\n"
        val path = Path.of("build/reports/g6-turn-order/$label.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, bytes)
        return Report(rows, starts.toList(), handled.toList(), expected, resolutions, bytes,
            EncounterResolver.BATTLE_RECORD_KEY in world.getGeneralById(1)!!.meta)
    }

    fun assertOneEncounter(report: Report) {
        assertTrue(report.battleRecordPresent, "real encounter must settle independently of evidence hook")
        assertEquals(1, report.resolutions.count { it == "Resolved" })
        assertEquals(1, report.rows.size, "G6 one resolved encounter must produce exactly one metric row")
        val row = report.rows.single()
        assertTrue(row.encounterId.isNotBlank())
        assertEquals(report.expectedOrder, report.starts)
        assertEquals(report.starts.indexOf(row.attacker.generalId) + 1, row.attacker.ordinal)
        assertTrue(row.outcome.isNotBlank())
        assertTrue(row.winners.isNotEmpty())
    }
}
