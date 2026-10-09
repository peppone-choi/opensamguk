package opensamguk.engine.invariance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.PersonalTurn
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.PersonalTravelCondition
import opensamguk.logic.input.Phase
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.WorldFormat
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** G3-only in-memory oracle. It never drains world buffers or uses the pinned gameplay baseline. */
internal class G3PersonalTurnReplayFixture {
    enum class Probe { NONE, HIDDEN_STATE, INPUT_TAMPER }
    data class Input(
        val phase: Phase, val generalId: Int, val turnTime: Instant,
        val requestId: String, val ownerUserId: Int, val actionCode: String, val argJson: String,
    ) {
        fun reservation() = ReservedTurn(actionCode, argJson, requestId = requestId,
            reservationOwnerUserId = ownerUserId, reservationRevision = "revision-$requestId")
    }
    data class Frame(
        val generalId: Int, val turnTime: Instant, val phase: Phase,
        val inputHash: String, val beforeStateHash: String, val afterStateHash: String,
        val logHash: String, val outcomeHash: String, val changeHash: String,
        val previousHash: String, val linkHash: String,
    )
    data class Replay(
        val initialStateHash: String, val inputLogHash: String, val initialLink: String,
        val frames: List<Frame>, val finalStateHash: String, val finalLogHash: String,
        val applied: Int, val consumed: List<String>, val readRequests: List<String>,
    )

    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val start = Instant.parse("0200-01-01T00:00:00Z")
    private val actors = listOf(1, 10)
    val inputs: List<Input> = (0 until 36).flatMap { phaseIndex ->
        actors.mapIndexed { actorIndex, actor ->
            val training = (phaseIndex + actorIndex) % 2 == 0
            Input(Phase(200, 1, 1).plus(phaseIndex), actor,
                start.plusSeconds(3600L * phaseIndex + 10L * (actorIndex + 1)),
                "g3-$phaseIndex-$actor", 42 + actorIndex,
                if (training) "action.selfTrain" else "action.recuperate",
                if (training) """{"stat":"strength"}""" else "{}")
        }
    }

    fun replay(label: String, probe: Probe = Probe.NONE): Replay {
        val world = initialWorld()
        val queues = actors.associateWith { id -> inputs.filter { it.generalId == id }.toMutableList() }
        val initial = stateHash(world, queues)
        val inputLogHash = hash(inputs)
        val initialLink = hash(listOf(FORMAT, HIDDEN_SEED, initial, inputLogHash))
        val consumed = mutableListOf<String>()
        val readRequests = mutableListOf<String>()
        var ordinal = 0
        var delivered: ReservedTurn? = null
        val handler = ReservedTurnHandler(world, registry = mock(), hiddenSeed = HIDDEN_SEED, startYear = 200)
        val lifecycle = TurnDaemonLifecycle(world, handler,
            movementOf = { _, _, outcome ->
                // Real field-action decision hook; restore next turn to make final-state-only checks blind.
                if (probe == Probe.HIDDEN_STATE && outcome == null) {
                    if (ordinal == MUTATION_TURN) changeStrength(world, 1)
                    if (ordinal == MUTATION_TURN + 1) changeStrength(world, -1)
                }
            },
            pullGeneralTurnOf = { id, selected ->
                val input = queues.getValue(id).removeAt(0)
                assertEquals(input.requestId, selected.requestId)
                consumed += input.requestId
            },
            reservedActionOf = { id ->
                val reserved = queues.getValue(id).first().reservation().let {
                    if (probe == Probe.INPUT_TAMPER && ordinal == MUTATION_TURN)
                        it.copy(argJson = """{"stat":"leadership"}""") else it
                }
                readRequests += checkNotNull(reserved.requestId)
                delivered = reserved
                reserved
            })
        val frames = mutableListOf<Frame>()
        var previous = initialLink
        inputs.forEachIndexed { index, input ->
            ordinal = index
            world.setCurrentDate(input.phase.year, input.phase.month, input.phase.phase)
            val before = stateHash(world, queues)
            val logCount = world.peekLogs().size
            delivered = null
            val runTime = input.turnTime.plusSeconds(1)
            assertEquals(listOf(input.generalId), lifecycle.dueGenerals(runTime).map { it.id })
            val result = lifecycle.runTick(runTime).single()
            assertEquals(input.generalId, result.generalId)
            assertEquals(input.requestId, result.requestId)
            assertEquals(input.actionCode, result.reservedActionCode)
            assertIs<TurnOutcome.Applied>(result.inputOutcome, "G3 input $index must exercise an applied handler")
            val actor = checkNotNull(world.getGeneralById(input.generalId))
            assertEquals(input.turnTime.plusSeconds(3600), actor.turnTime, "complete personal-turn tail")
            assertEquals(PersonalTurn.after(emptyMap(), world.getState())[PersonalTurn.META_KEY],
                actor.meta[PersonalTurn.META_KEY], "phase stamp after runTick returns")
            val logs = world.peekLogs().drop(logCount)
            assertTrue(logs.isNotEmpty(), "applied personal action must produce a real log")
            val after = stateHash(world, queues)
            val actualInput = hash(listOf(input.phase, input.generalId, input.turnTime, checkNotNull(delivered)))
            val logHash = hash(logs)
            val outcomeHash = hash(listOf("Applied", result.inputOutcome, result.args, result.logs))
            val changeHash = hash(listOf(FORMAT, before, after, logHash, outcomeHash))
            val link = hash(listOf(FORMAT, previous, input.turnTime, input.generalId, input.phase, actualInput, changeHash))
            frames += Frame(input.generalId, input.turnTime, input.phase, actualInput, before, after,
                logHash, outcomeHash, changeHash, previous, link)
            previous = link
        }
        assertTrue(queues.values.all { it.isEmpty() }, "all reservations consumed")
        assertEquals(inputs.map { it.requestId }, consumed, "exact input consumption order, once each")
        assertEquals(consumed, readRequests, "all inputs read by the real lifecycle, once each")
        assertEquals(72, frames.size)
        val result = Replay(initial, inputLogHash, initialLink, frames, stateHash(world, queues),
            hash(world.peekLogs()), frames.size, consumed.toList(), readRequests.toList())
        val report = Path.of("build/reports/g3-personal-turn/$label.json")
        Files.createDirectories(report.parent)
        Files.writeString(report, canonical(mapper.valueToTree(result)) + "\n")
        println("G3_REPLAY label=$label turns=${result.frames.size} applied=${result.applied} " +
            "initial=$initial inputs=$inputLogHash final=${result.finalStateHash} chain=$previous")
        return result
    }

    fun assertEquivalent(expected: Replay, actual: Replay) {
        assertEquals(expected.initialStateHash, actual.initialStateHash, "G3 initial state")
        assertEquals(expected.inputLogHash, actual.inputLogHash, "G3 recorded input log")
        assertEquals(expected.initialLink, actual.initialLink, "G3 initial chain link")
        assertEquals(expected.frames.size, actual.frames.size, "G3 turn count")
        expected.frames.zip(actual.frames).forEachIndexed { index, (left, right) ->
            assertEquals(left, right, "G3 personal turn $index (general ${left.generalId}, ${left.phase})")
        }
        assertEquals(expected.finalStateHash, actual.finalStateHash, "G3 final state")
        assertEquals(expected.finalLogHash, actual.finalLogHash, "G3 final ordered logs")
        assertEquals(expected.consumed, actual.consumed, "G3 input consumption")
    }

    private fun initialWorld(): InMemoryTurnWorld {
        val positions = actors.fold(GeneralPositionSnapshot("g3-topology-1", "d".repeat(64), setOf("p1"), emptySet())) {
                snapshot, id -> snapshot.withState(GeneralPositionState("g3-topology-1", "d".repeat(64), id,
                    StrategicNodeRef.LandProvince("p1"), 1))
        }
        val people = actors.mapIndexed { index, id ->
            TurnGeneral(id = id, name = "G$id", nationId = 1, cityId = 5, troopId = 0,
                stats = GeneralStats(70, 40, 60, 70, 70), experience = 0, dedication = 0, officerLevel = 1,
                userId = "${42 + index}", npcState = 0, injury = 10, age = 30,
                turnTime = start.plusSeconds(10L * (index + 1)),
                meta = mapOf(PersonalTravelCondition.META_KEY to PersonalTravelCondition.INITIAL.copy(fatigue = 10).toMetaValue(),
                    "g3Pins" to mapOf("actorId" to id, "turnTime" to start.toString(), "inputHash" to "initial-pin")))
        }
        return InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(893, 200, 1, 3600, start,
                config = mapOf(WorldFormat.CONFIG_KEY to WorldFormat.GENERAL_RETAINER_CAMPAIGN.name, "mapName" to "han-world-v3")),
            worldId = WorldId(893), generals = people,
            cities = listOf(City(5, "C5", 1, 5)), nations = listOf(Nation(1, "N1", "#111111")),
            generalPositionSnapshot = positions, cityLandProvinceById = mapOf(5 to "p1"), administrativeCountyIds = setOf(5)))
    }

    private fun changeStrength(world: InMemoryTurnWorld, amount: Int) {
        val before = checkNotNull(world.getGeneralById(1))
        world.applyGeneralDirtyFree(before.copy(stats = before.stats.copy(strength = before.stats.strength + amount)))
    }

    private fun stateHash(world: InMemoryTurnWorld, queues: Map<Int, List<Input>>): String {
        val snapshot = WorldSnapshot(worldId = world.worldId, state = world.getState(),
            generals = world.listGenerals().sortedBy { it.id }, cities = world.listCities().sortedBy { it.id },
            nations = world.listNations().sortedBy { it.id }, troops = world.listTroops().sortedBy { it.id },
            diplomacy = world.listDiplomacy().sortedWith(compareBy({ it.fromNationId }, { it.toNationId })),
            accessLogs = world.listAccessLogs().sortedBy { it.generalId }, retainers = world.listRetainers().sortedBy { it.id },
            bugoks = world.listBugoks().sortedBy { it.id }, operations = world.listOperations().sortedBy { it.id },
            operationUnits = world.listOperationUnits().sortedBy { it.id }, battlePlans = world.listBattlePlans().sortedBy { it.id },
            sieges = world.listSieges().sortedBy { it.countyId }, waterControlSnapshot = world.waterControlSnapshot(),
            provinceControlSnapshot = world.provinceControlSnapshot(), generalPositionSnapshot = world.generalPositionSnapshot(),
            cityLandProvinceById = mapOf(5 to "p1"), administrativeCountyIds = world.administrativeCountyIds)
        return hash(listOf(FORMAT, snapshot, queues.toSortedMap()))
    }

    fun hash(value: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(mapper.valueToTree(value)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun canonical(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
            mapper.writeValueAsString(it) + ":" + canonical(node[it])
        }
        node.isArray -> node.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> node.toString()
    }

    companion object {
        const val MUTATION_TURN = 8
        private const val FORMAT = "g3-personal-turn-v1"
        private const val HIDDEN_SEED = "g3-fixed-synthetic-seed"
    }
}
