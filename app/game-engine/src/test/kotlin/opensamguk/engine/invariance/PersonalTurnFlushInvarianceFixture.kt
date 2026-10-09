package opensamguk.engine.invariance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** G4-only synthetic actors on the real archived map; no public hash contract. */
internal class PersonalTurnFlushInvarianceFixture(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
) {
    enum class Schedule { BATCH, PER_ACTOR }
    enum class Probe { NONE, DROP_SINGLE_GENERAL_UPDATE, FLUSH_ONLY_NEXT_ACTOR }
    data class Run(
        val initial: JsonNode, val persisted: JsonNode, val cold: JsonNode,
        val hotStrength: Int, val coldStrength: Int, val flushes: Int,
        val handled: List<ReservedTurnHandler.HandledTurn>, val versions: Map<String, Long>,
    )
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val start = Instant.parse("0200-01-01T00:00:00Z")
    private val root = Path.of("build/reports/g4-flush-invariance")
    private val requests = listOf("g4-gift-1", "g4-train-10")
    private val revision = "00000000-0000-0000-0000-000000000893"

    fun run(schedule: Schedule, probe: Probe): Run {
        jdbc.execute("TRUNCATE world_state RESTART IDENTITY CASCADE")
        lateinit var active: InMemoryTurnWorld
        var flushCount = 0
        val executor = object : JdbcFlushExecutor(NamedParameterJdbcTemplate(jdbc), transaction) {
            override fun flush(payload: FlushPayload) {
                // Execute the real SQL transaction and every other write channel unchanged.
                val actual = if (probe == Probe.DROP_SINGLE_GENERAL_UPDATE && payload.updatedGenerals.size == 1)
                    payload.copy(updatedGenerals = emptyList()) else payload
                super.flush(actual)
                flushCount++
                // The same flush-only defect in BOTH schedules: change a still-due actor projection.
                // The subsequent real PersonalHandler must read this value and reject training at cap.
                if (probe == Probe.FLUSH_ONLY_NEXT_ACTOR) {
                    val next = active.getGeneralById(10)!!
                    if (next.turnTime == start.plusSeconds(20))
                        active.applyGeneralDirtyFree(next.copy(stats = next.stats.copy(strength = 80)))
                }
            }
        }
        val fixture = EnlistmentFixture(jdbc, executor)
        fixture.seed(893)
        seedInputs()
        assertEquals(listOf(revision, revision, revision, revision), jdbc.queryForList(
            "SELECT reservation_revision::text FROM general_turn WHERE world_id=893 ORDER BY general_id,turn_idx", String::class.java))
        val repository = ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc))
        listOf(1, 10).forEachIndexed { index, actor ->
            val reserved = repository.readReserved(WorldId(893), actor, 0)
            assertTrue(reserved.rowExists)
            assertEquals(requests[index], reserved.requestId)
            assertEquals(42 + index, reserved.reservationOwnerUserId)
            assertEquals(revision, reserved.reservationRevision)
            assertEquals(mapper.readTree(if (actor == 1)
                """{"targetGeneralId":10,"resource":"MONEY","amount":40}""" else """{"stat":"strength"}"""),
                mapper.readTree(reserved.argJson))
        }
        val initial = persisted()
        val initialSnapshot = fixture.load(893)
        assertEquals(0L, initialSnapshot.state.worldVersion)
        assertEquals(0L, jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=893", Long::class.java))
        active = InMemoryTurnWorld(initialSnapshot)
        val published = mutableListOf<String>()
        val service = fixture.service(WorldId(893), active, published)
        val handled = if (schedule == Schedule.BATCH) service.runDueGeneralTurns(start.plusSeconds(21)).handled
            else service.runDueGeneralTurns(start.plusSeconds(11)).handled +
                service.runDueGeneralTurns(start.plusSeconds(21)).handled
        assertEquals(listOf(1, 10), handled.map { it.generalId })
        assertEquals(requests, handled.map { it.requestId })
        assertEquals(listOf("action.gift", "action.selfTrain"), handled.map { it.reservedActionCode })
        val expectedCount = if (schedule == Schedule.BATCH) 1 else 2
        assertEquals(expectedCount, flushCount)
        assertEquals(expectedCount.toLong(), jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=893", Long::class.java))
        val cold = fixture.load(893) // New loader and new JDBC queries, no hot snapshot oracle.
        assertEquals(expectedCount.toLong(), cold.state.worldVersion)
        val versions = jdbc.queryForList("SELECT request_id,committed_world_version FROM command_result WHERE world_id=893 ORDER BY request_id")
            .associate { it["request_id"].toString() to (it["committed_world_version"] as Number).toLong() }
        assertEquals(mapOf(requests[0] to 1L, requests[1] to expectedCount.toLong()), versions)
        assertEquals(requests, published)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE world_id=893 AND published_at IS NOT NULL", Int::class.java))
        val queue = jdbc.queryForList("SELECT general_id,turn_idx,action_code,arg::text,request_id,reservation_revision::text FROM general_turn WHERE world_id=893 ORDER BY general_id,turn_idx")
        assertEquals(2, queue.size)
        queue.forEach { row ->
            assertEquals(0, row["turn_idx"])
            assertEquals("action.recuperate", row["action_code"])
            assertEquals("{}", row["arg"])
            assertNull(row["request_id"])
            assertNotEquals(revision, row["reservation_revision"])
            val restoredReservation = repository.readReserved(WorldId(893), (row["general_id"] as Number).toInt(), 0)
            assertEquals(row["reservation_revision"], restoredReservation.reservationRevision)
            assertEquals("action.recuperate", restoredReservation.actionCode)
            assertEquals("{}", restoredReservation.argJson)
            assertNull(restoredReservation.requestId)
            assertNull(restoredReservation.reservationOwnerUserId)
        }
        // readReserved is the real DB owner binding path, not a fabricated ReservedTurn.
        assertEquals(listOf(42, 43), jdbc.queryForList("SELECT owner_user_id FROM command_inbox WHERE world_id=893 ORDER BY general_id", Int::class.java))
        val result = Run(initial, persisted(), coldProjection(cold), active.getGeneralById(10)!!.stats.strength,
            cold.generals.single { it.id == 10 }.stats.strength, flushCount, handled, versions)
        write("${probe.name}-${schedule.name}", result)
        return result
    }

    private fun seedInputs() {
        jdbc.update("UPDATE general SET nation_id=1,npc_state=0,user_id=42,turn_time='0200-01-01T00:00:10Z' WHERE world_id=893 AND id=1")
        jdbc.update("UPDATE general SET npc_state=0,user_id=43,turn_time='0200-01-01T00:00:20Z' WHERE world_id=893 AND id=10")
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=893 AND id=2")
        listOf(Triple(1, "action.gift", """{"targetGeneralId":10,"resource":"MONEY","amount":40}"""),
            Triple(10, "action.selfTrain", """{"stat":"strength"}""")).forEachIndexed { index, (actor, code, args) ->
            jdbc.update("""INSERT INTO command_inbox(world_id,request_id,payload_schema_version,command_kind,status,
                intent_fingerprint,general_id,turn_idx,action_code,payload,owner_user_id)
                VALUES (893,?,1,'RESERVED_TURN','ACCEPTED',?,?,0,?,'{}'::jsonb,?)""",
                requests[index], "g4-fixed-$actor", actor, code, 42 + index)
            jdbc.update("""INSERT INTO general_turn(world_id,general_id,turn_idx,action_code,arg,request_id,reservation_revision)
                VALUES (893,?,0,?,?::jsonb,?,?::uuid),(893,?,1,'action.recuperate','{}'::jsonb,NULL,?::uuid)""",
                actor, code, args, requests[index], revision, actor, revision)
        }
    }

    /** Include every migrated table with world_id, including untouched satellites; retain row/array order. */
    private fun persisted(): JsonNode {
        val result = mapper.createObjectNode()
        val tables = jdbc.queryForList("""SELECT table_name FROM information_schema.columns
            WHERE table_schema='public' AND column_name='world_id' ORDER BY table_name""", String::class.java)
        for (table in listOf("world_state") + tables) {
            require(table.matches(Regex("[a-z][a-z0-9_]*")))
            val predicate = if (table == "world_state") "id=893" else "world_id=893"
            val order = when (table) {
                "log_entry" -> "id"
                "game_event" -> "occurred_year,occurred_month,occurred_phase,occurred_ordinal,id"
                else -> "to_jsonb(t)::text"
            }
            val rows = mapper.createArrayNode()
            jdbc.queryForList("SELECT to_jsonb(t)::text FROM $table t WHERE $predicate ORDER BY $order", String::class.java)
                .forEach { json -> rows.add(normalizeRow(table, mapper.readTree(json) as ObjectNode)) }
            // Rowsets have semantic keys; normalization cannot reorder event/log arrays.
            val sorted = if (table in setOf("log_entry", "game_event")) rows else mapper.valueToTree<JsonNode>(rows.toList().sortedBy { canonical(it) })
            result.set<JsonNode>(table, sorted)
        }
        return result
    }

    private fun normalizeRow(table: String, row: ObjectNode): JsonNode {
        val clockColumns = when (table) {
            "world_state", "general", "city", "nation", "general_retainers", "general_bugok", "command_inbox", "command_result" -> listOf("created_at", "updated_at")
            "general_turn", "log_entry", "command_outbox" -> listOf("created_at")
            "game_event" -> listOf("recorded_at")
            else -> emptyList()
        }
        row.remove(clockColumns)
        when (table) {
            "world_state" -> row.remove("world_version")
            "general_turn" -> row.remove("reservation_revision")
            "command_result" -> {
                validateEnvelope(row["result_payload"], row["committed_world_version"].asLong())
                row.remove(listOf("committed_world_version", "sent_at"))
                (row["result_payload"] as ObjectNode).remove(listOf("committedWorldVersion", "sentAt"))
            }
            "command_outbox" -> {
                row.put("published", !row["published_at"].isNull)
                row.remove("published_at")
                val payload = row["payload"] as ObjectNode
                val storedVersion = jdbc.queryForObject(
                    "SELECT committed_world_version FROM command_result WHERE world_id=893 AND request_id=?",
                    Long::class.java, row["request_id"].asText())!!
                validateEnvelope(payload, storedVersion)
                payload.remove(listOf("committedWorldVersion", "sentAt"))
            }
        }
        return row
    }

    private fun validateEnvelope(payload: JsonNode, version: Long) {
        assertEquals(version, payload["committedWorldVersion"].asLong())
        assertNotNull(payload["sentAt"])
    }

    private fun coldProjection(snapshot: WorldSnapshot): JsonNode = mapper.valueToTree(mapOf(
        "snapshot" to snapshot.copy(state = snapshot.state.copy(worldVersion = 0), waterControlSnapshot = null,
            provinceControlSnapshot = null, generalPositionSnapshot = null),
        "water" to snapshot.waterControlSnapshot?.let { listOf(it.topologyRevision, it.topologyHash, it.statesByZoneId) },
        "province" to snapshot.provinceControlSnapshot?.let { listOf(it.topologyRevision, it.topologyHash, it.statesByProvinceId) },
        "position" to snapshot.generalPositionSnapshot?.let { listOf(it.topologyRevision, it.topologyHash, it.statesByGeneralId) },
    ))

    fun assertEquivalent(batch: Run, separate: Run) {
        assertEquals(hash(batch.initial), hash(separate.initial), "identical persisted initial state")
        assertEquals(hash(batch.persisted), hash(separate.persisted), "actual PostgreSQL final state must be flush invariant")
        assertEquals(hash(batch.cold), hash(separate.cold), "independent production cold reload must be flush invariant")
    }

    fun hash(value: JsonNode): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonNode): String = when {
        value.isObject -> value.fields().asSequence().sortedBy { it.key }.joinToString(",", "{", "}") { mapper.writeValueAsString(it.key) + ":" + canonical(it.value) }
        value.isArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    private fun write(name: String, run: Run) {
        Files.createDirectories(root)
        Files.writeString(root.resolve("$name.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "world" to 893, "phase" to "200-1-1", "schedule" to name, "initialHash" to hash(run.initial),
            "persistedHash" to hash(run.persisted), "coldHash" to hash(run.cold), "actualFlushes" to run.flushes,
            "committedVersions" to run.versions, "orderedActorIds" to run.handled.map { it.generalId },
            "orderedOutcomes" to run.handled.map { it.inputOutcome.toString() },
            "hotStrength" to run.hotStrength, "coldStrength" to run.coldStrength,
            "initialPersisted" to run.initial, "persisted" to run.persisted, "coldReload" to run.cold)))
    }
}
