package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId

/**
 * FF2 — Testcontainers IT for the [JdbcFlushExecutor] satellite write-set (rank_data step-8,
 * nation step-7 UPDATE, nation_id sync, nation_env KV step-10) + the widened step-7 general/city
 * UPDATE SET.
 *
 * Brings up `postgres:16-alpine`, applies V1+V2, pre-seeds the 37 rank_data rows per general (PHP
 * seeds them at general creation — the flush UPDATEs, never UPSERTs), then flushes a payload that:
 *  - increments rank_data `occupied` (+1) and SETs `warnum` (=3) on general 10,
 *  - moves general 10's nation 2 → 3 (the nation_id-sync op rewrites ALL its rank_data rows),
 *  - UPDATEs nation 3 gold/meta,
 *  - writes two nation_env KV keys (a bare int `next_execute_*` and a delete-on-null key),
 *  - changes general 10 crew/train/atmos/last_turn AND city 5 secu/def/wall/pop (widened SET).
 *
 * Asserts the rows read back byte-comparable AND the recorded op sequence == the frozen contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcFlushExecutorSatelliteIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
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
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
        val txManager = DataSourceTransactionManager(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(txManager))

        seed()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun seed() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'scenario_2', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO nation (world_id, id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta)
            VALUES (1, 2, '촉', '#00ff00', 5, 5000, 5000, 1000, 5, 'che_명가', CAST('{"rate":20,"bill":20}' AS jsonb)),
                   (1, 3, '위', '#0000ff', 8, 8000, 8000, 2000, 7, 'che_명가', CAST('{"rate":20,"bill":20}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, crew, train, atmos,
                 crew_type_id, troop_id, weapon_code, book_code, horse_code, item_code,
                 npc_state, turn_time, last_turn, meta)
            VALUES
                (1, 10, '장수십', 2, 5, 70, 65, 80, 0, 0, 0, 4, 1000, 1000, 0, 0, 0,
                 0, 0, 'None', 'None', 'None', 'None', 0, now(),
                 CAST('{"command":"휴식"}' AS jsonb),
                 CAST('{"explevel":1}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (1, 5, '성도', 5, 2, 1, 0, 50000, 100000,
                 1000, 2000, 800, 2000, 500, 1000, 50, 100, 1000, 2000,
                 1000, 2000, 1, CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // Pre-seed the 37 rank_data rows for general 10 (PHP seeds them at creation; flush UPDATEs).
        for (col in RANK_COLUMNS) {
            jdbc.update(
                "INSERT INTO rank_data (world_id, nation_id, general_id, type, value) VALUES (1, 2, 10, :type, :value)",
                MapSqlParameterSource().addValue("type", col).addValue("value", if (col == "warnum") 1 else 0),
            )
        }
        // Pre-seed a stale nation_env KV row so the delete-on-null write proves a real DELETE.
        jdbc.update(
            "INSERT INTO nation_env (world_id, namespace, key, value) VALUES (1, 3, 'stale_key', CAST('1' AS jsonb))",
            MapSqlParameterSource(),
        )
    }

    @Test
    fun `flush writes the satellite write-set byte-comparable in the frozen op order`() {
        val postGeneral = General(
            id = 10, nationId = 3, cityId = 5,   // nation 2 -> 3 (triggers rank_data nation_id sync)
            leadership = 70, strength = 65, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 4, gold = 1000, rice = 1000,
            crew = 500, train = 80.0, atmos = 70.0,
            crewTypeId = 0, troop = 0,
            lastTurn = LastTurn(command = "출병", arg = linkedMapOf("destCityID" to 8)),
            meta = linkedMapOf("explevel" to 1),
        )
        val postNation = Nation(
            id = 3, level = 7, capitalCityId = 8,
            name = "위", color = "#0000ff", typeCode = "che_명가",
            gold = 7500, rice = 8000, tech = 2000.0,
            meta = linkedMapOf("rate" to 20, "bill" to 25),  // bill 20 -> 25
        )
        val postCity = City(
            id = 5, nationId = 2, level = 5,
            commerce = 800, commerceMax = 2000,
            agriculture = 1000, agricultureMax = 2000,
            supplyState = 1, frontState = 0, trust = 50.0,
            security = 650, securityMax = 1000,
            defense = 1100, defenseMax = 2000,
            wall = 1200, wallMax = 2000,
            population = 49000, populationMax = 100000,
            trade = 100, region = 1,
            meta = linkedMapOf(),
        )

        val payload = testFlushPayload(
            worldId = opensamguk.common.world.WorldId(1),
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 2),
            updatedGenerals = listOf(postGeneral),
            updatedCities = listOf(postCity),
            updatedNations = listOf(postNation),
            rankWrites = listOf(
                RankWrite(generalId = 10, type = "occupied", op = RankFlushOp.Increment(1)),
                RankWrite(generalId = 10, type = "warnum", op = RankFlushOp.Set(3)),
            ),
            rankNationSync = listOf(RankNationSync(generalId = 10, nationId = 3)),
            kvWrites = listOf(
                KvWrite.nationEnv(namespace = 3, key = "next_execute_300", value = 200),
                KvWrite.nationEnv(namespace = 3, key = "stale_key", value = null),  // delete-on-null
            ),
        )

        executor.flush(payload)

        // --- op order == the frozen contract (world_state, general+city+nation UPDATE, rank_data) --
        assertEquals(
            listOf(
                FlushExecOp("world_state", FlushVerb.UPDATE, 1),
                FlushExecOp("general", FlushVerb.UPDATE, 1),
                FlushExecOp("city", FlushVerb.UPDATE, 1),
                FlushExecOp("nation", FlushVerb.UPSERT, 1),
                FlushExecOp("rank_data", FlushVerb.UPDATE, 2),    // 1 increment + 1 set
                FlushExecOp("rank_data", FlushVerb.UPDATE, 1),    // nation_id sync (1 general)
                FlushExecOp("kv", FlushVerb.UPSERT, 2),           // 1 set + 1 delete (nation_env int-ns)
            ),
            executor.lastOps(),
        )

        // --- rank_data byte-comparable: occupied += 1 -> 1, warnum set -> 3, nation_id synced -> 3 --
        val occupied = rankValue(generalId = 10, type = "occupied")
        assertEquals(1, occupied)
        assertEquals(3, rankValue(generalId = 10, type = "warnum"))
        // ALL of general 10's rank_data rows now carry nation_id 3 (the sync op).
        val distinctNationIds = jdbc.queryForList(
            "SELECT DISTINCT nation_id FROM rank_data WHERE world_id = 1 AND general_id = 10",
            MapSqlParameterSource(),
            Int::class.java,
        )
        assertEquals(listOf(3), distinctNationIds)
        assertEquals(
            37,
            jdbc.queryForObject(
                "SELECT count(*) FROM rank_data WHERE world_id = 1 AND general_id = 10",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )

        // --- widened general step-7: crew/train/atmos/last_turn flushed ---------------------------
        val gRow = jdbc.queryForMap(
            "SELECT nation_id, crew, train, atmos, last_turn::text AS last_turn FROM general WHERE world_id = 1 AND id = 10",
            MapSqlParameterSource(),
        )
        assertEquals(3, intOf(gRow["nation_id"]))
        assertEquals(500, intOf(gRow["crew"]))
        assertEquals(80, intOf(gRow["train"]))
        assertEquals(70, intOf(gRow["atmos"]))
        assertEquals(
            mapOf<String, Any?>("command" to "출병", "arg" to mapOf("destCityID" to 8)),
            MetaJson.decode(stringOf(gRow["last_turn"])),
        )

        // --- widened city step-7: secu/def/wall/pop flushed ---------------------------------------
        val cRow = jdbc.queryForMap(
            "SELECT secu, def, wall, pop FROM city WHERE world_id = 1 AND id = 5",
            MapSqlParameterSource(),
        )
        assertEquals(650, intOf(cRow["secu"]))
        assertEquals(1100, intOf(cRow["def"]))
        assertEquals(1200, intOf(cRow["wall"]))
        assertEquals(49000, intOf(cRow["pop"]))

        // --- nation step-7 UPDATE: gold + meta bill ------------------------------------------------
        val nRow = jdbc.queryForMap(
            "SELECT gold, meta::text AS meta FROM nation WHERE world_id = 1 AND id = 3",
            MapSqlParameterSource(),
        )
        assertEquals(7500, intOf(nRow["gold"]))
        assertEquals(mapOf<String, Any?>("rate" to 20, "bill" to 25), MetaJson.decode(stringOf(nRow["meta"])))

        // --- nation_env KV step-10: set key present, delete-on-null key absent ---------------------
        assertEquals(200, kvValue(namespace = 3, key = "next_execute_300"))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM nation_env WHERE world_id = 1 AND namespace = 3 AND key = 'stale_key'",
                MapSqlParameterSource(),
                Int::class.java,
            ),
            "delete-on-null removed the pre-seeded stale_key row",
        )
    }

    private fun seedQueueWorld(id: Int, campaign: Boolean = true) {
        val params = mapOf("world" to id, "config" to if (campaign) "{\"worldFormat\":\"GENERAL_RETAINER_CAMPAIGN\"}" else "{}")
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,config)
            VALUES (:world,'synthetic-retire-clear',200,1,3600,CAST(:config AS jsonb))""", params)
        for (actor in listOf(10, 11, 12)) {
            val p = params + mapOf("actor" to actor, "owner" to actor + 100, "name" to "synthetic-$actor")
            jdbc.update("""INSERT INTO general(world_id,id,name,npc_state,turn_time,last_turn,meta)
                VALUES (:world,:actor,:name,5,now(),'{}'::jsonb,'{"retired":true}'::jsonb)""", p)
            jdbc.update("INSERT INTO general_owner(world_id,general_id,user_id) VALUES (:world,:actor,:owner)", p)
            for (slot in 0 until if (campaign) 12 else 30) jdbc.update("""INSERT INTO general_turn
                (world_id,general_id,turn_idx,action_code,arg,request_id)
                VALUES (:world,:actor,:slot,'action.personal.train','{}'::jsonb,:request)""",
                p + mapOf("slot" to slot, "request" to "synthetic-$id-$actor-$slot"))
        }
        val receipt = params + mapOf("request" to "synthetic-cancel-$id", "event" to "synthetic-cancel-event-$id")
        jdbc.update("""INSERT INTO command_inbox(world_id,request_id,payload_schema_version,command_kind,status,
            intent_fingerprint,general_id,turn_idx,action_code,payload,owner_user_id)
            VALUES (:world,:request,1,'QUEUE_MUTATION','APPLIED','synthetic',10,0,'cancelReservedTurn','{}'::jsonb,110)""", receipt)
        jdbc.update("""INSERT INTO command_result(world_id,request_id,result_seq,terminal_status,result_type,ok,
            committed_world_version,payload_schema_version,result_payload,sent_at)
            VALUES (:world,:request,1,'APPLIED','reservationCancelled',true,0,1,'{}'::jsonb,now())""", receipt)
        jdbc.update("""INSERT INTO command_outbox(world_id,event_id,request_id,event_type,payload_schema_version,payload)
            VALUES (:world,:event,:request,'commandResult',1,'{}'::jsonb)""", receipt)
    }

    private fun queueRows(worlds: List<Int>) = jdbc.queryForList(
        "SELECT * FROM general_turn WHERE world_id IN (:worlds) ORDER BY world_id,general_id,turn_idx", mapOf("worlds" to worlds))

    private fun queuePayload(id: Int) = testFlushPayload(WorldId(id),
        mapOf("id" to id, "current_year" to 200, "current_month" to 1, "current_phase" to 1))

    @Test
    fun `campaign clear removes all twelve actor slots and preserves owners receipts other actors worlds and frozen profile`() {
        val campaign = 901
        val otherWorld = 902
        val sammo = 903
        seedQueueWorld(campaign); seedQueueWorld(otherWorld); seedQueueWorld(sammo, campaign = false)
        val worlds = listOf(campaign, otherWorld, sammo)
        val before = queueRows(worlds)
        val preserved = listOf("general_owner", "command_inbox", "command_result", "command_outbox").associateWith {
            jdbc.queryForList("SELECT * FROM $it WHERE world_id IN (:worlds) ORDER BY 1,2", mapOf("worlds" to worlds))
        }
        val payload = queuePayload(campaign).copy(generalTurnClears = listOf(10, 10))
        executor.flush(payload)
        assertEquals(before.filterNot { (it["world_id"] as Number).toInt() == campaign &&
            (it["general_id"] as Number).toInt() == 10 }, queueRows(worlds))
        assertEquals(FlushExecOp("general_turn_clear", FlushVerb.DELETE_MANY, 12), executor.lastOps().last())
        executor.flush(queuePayload(sammo).copy(generalTurnClears = listOf(10)))
        assertTrue(executor.lastOps().none { it.table == "general_turn_clear" })
        preserved.forEach { (table, rows) -> assertEquals(rows,
            jdbc.queryForList("SELECT * FROM $table WHERE world_id IN (:worlds) ORDER BY 1,2", mapOf("worlds" to worlds))) }
        assertEquals(30, queueRows(listOf(sammo)).count { (it["general_id"] as Number).toInt() == 10 })
    }

    @Test
    fun `campaign clear takes the actor lock and a lock failure leaves the whole queue intact`() {
        val id = 904
        seedQueueWorld(id)
        val before = queueRows(listOf(id))
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.prepareStatement("SELECT id FROM general WHERE world_id=? AND id=10 FOR UPDATE").use {
                it.setInt(1, id)
                it.executeQuery().close()
            }
            try {
                val error = assertFailsWith<org.springframework.dao.DataAccessException> {
                    TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                        jdbc.jdbcTemplate.execute("SET LOCAL lock_timeout='100ms'")
                        executor.flush(queuePayload(id).copy(generalTurnClears = listOf(10)))
                    }
                }
                assertTrue(generateSequence<Throwable>(error) { it.cause }.filterIsInstance<java.sql.SQLException>()
                    .any { it.sqlState == "55P03" }, "the clear must contend on the actor lock")
                assertEquals(before, queueRows(listOf(id)))
            } finally { blocker.rollback() }
        }
    }

    private fun rankValue(generalId: Int, type: String): Int =
        jdbc.queryForObject(
            "SELECT value FROM rank_data WHERE world_id = 1 AND general_id = :g AND type = :t",
            MapSqlParameterSource().addValue("g", generalId).addValue("t", type),
            Int::class.java,
        ) ?: error("no rank_data row $generalId/$type")

    private fun kvValue(namespace: Int, key: String): Int? =
        jdbc.queryForList(
            "SELECT value::text AS v FROM nation_env WHERE world_id = 1 AND namespace = :n AND key = :k",
            MapSqlParameterSource().addValue("n", namespace).addValue("k", key),
            String::class.java,
        ).firstOrNull()?.toInt()

    companion object {
        private val RANK_COLUMNS = listOf(
            "firenum", "warnum", "killnum", "deathnum", "killcrew", "deathcrew",
            "ttw", "ttd", "ttl", "ttg", "ttp",
            "tlw", "tld", "tll", "tlg", "tlp",
            "tsw", "tsd", "tsl", "tsg", "tsp",
            "tiw", "tid", "til", "tig", "tip",
            "betwin", "betgold", "betwingold",
            "killcrew_person", "deathcrew_person",
            "occupied",
            "inherit_earned", "inherit_spent",
            "inherit_earned_dyn", "inherit_earned_act", "inherit_spent_dyn",
        )
    }
}
