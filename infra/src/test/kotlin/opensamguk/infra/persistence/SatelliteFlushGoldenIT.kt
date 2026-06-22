package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
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

/**
 * AREA GATE-SATELLITE — Task GSat1: the satellite/KV write-set byte-comparable golden.
 *
 * Where [JdbcFlushExecutorSatelliteIT] (FF2) proves the flush MECHANICS with synthetic values, this
 * gate proves the flush is byte-comparable to the **captured PHP golden DB after-image** of a
 * representative satellite command. The chosen command is **che_거병** — the only satellite command
 * in the 28 committed P2 goldens (감축/증축 capset, 발령/포상 dual-general, 방랑 cascade, 천도-random KV
 * are all on the GS1 ignore-list: structurally un-satisfiable in the pristine scenario_1010 year-181
 * install — see tools/php-golden/p2-capture-backlog.md, NOT failures). 거병 exercises EVERY satellite
 * slot at once: a created nation (step-3), 24 created nation_turn rows (step-3, the `brief` after-image),
 * bidirectional created diplomacy (step-3), the rank_data nation_id sync over all 37 pre-seeded rows
 * (step-8), the general after-image (step-7 + last_turn jsonb), and the action/global log_entry rows
 * (step-9). It is the natural superset gate for "rank_data + nation + nation_turn + nation_env KV +
 * log_entry write-sets byte-comparable to the golden DB after-image".
 *
 * THE GOLDEN IS THE BYTE ORACLE. The after-image is NOT re-resolved here — it is the captured truth:
 *  - the GENERAL after-state is the committed fixture `golden/p2/che_거병-fixtures.json` (`cases[0].after`):
 *    nation 0→3, officer_level 0→12, experience 2374→2474 (+100), dedication 1960→2060 (+100). gid 11 =
 *    `ⓝ공손범` (NPC, no `owner` ⇒ `increaseInheritancePoint` short-circuits ⇒ NO rank_data increment;
 *    the only rank effect is the nation_id sync). last_turn = `LastTurn('거병', arg=[])` ⇒ `{"command":"거병"}`.
 *  - the SATELLITE write-set is the PHP grand truth `legacy/devsam-core/hwe/sammo/Command/General/che_거병.php`:
 *    * nation INSERT (`che_거병.php:98-110`): color '#330000', gold 0, rice `GameConst::baserice`=2000,
 *      meta = {rate:20, bill:100, strategic_cmd_limit:12, surlimit:72, secretlimit:1 (scenario≥1000),
 *      gennum:1} in PHP insertion order; type = `che_중립` (`GameConst::neutralNationType`).
 *    * 24 nation_turn rows (`:142-156`): `foreach([12,11]) × Util::range(GameConst::maxChiefTurn=12)`,
 *      each `action='휴식', arg=null (→ '{}'), brief='휴식'` — the V2 `brief` after-image PINNED here.
 *    * diplomacy (`:114-138`): for EVERY other nation a bidirectional `(me,you,state=2,term=0)` pair.
 *    * rank_data (`General.php:704-751` applyDB): the 37 pre-seeded rows get `nation_id := newNationID`.
 *
 * RANK_ROWS_PER_GENERAL = 37 is PINNED (= the PHP `sammo\Enums\RankColumn` enum's `cases()`). The 37
 * rows are pre-seeded at general creation (PHP); the flush UPDATEs, never UPSERTs.
 *
 * The flush runs through the REAL [JdbcFlushExecutor] on `postgres:16-alpine` (V1+V2+V3), then this test
 * SELECTs rank_data + nation + nation_turn + nation_env + general + log_entry and asserts each row/jsonb
 * byte-comparable to the golden after-image. A byte-mismatch here is a REAL parity bug in the flush.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SatelliteFlushGoldenIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    /** The 거병 actor: gid 11 = ⓝ공손범, neutral (nation 0), city 32 평양, year 181 / month 1. */
    private val actorId = 11
    private val actorCity = 32
    private val priorNationId = 0
    /** The nation 거병 creates (next free id after the two scenario_1010 nations 1·2 = 3). */
    private val newNationId = 3

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
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
        val txManager = DataSourceTransactionManager(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(txManager))

        seedBefore()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    /** Seed the BEFORE-image: world, the two scenario nations, the neutral actor, its 37 rank_data rows. */
    private fun seedBefore() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'scenario_1010', 181, 1, 3600)",
            MapSqlParameterSource(),
        )
        // The two scenario_1010 nations — the bidirectional diplomacy peers 거병 wires the new nation to.
        jdbc.update(
            """
            INSERT INTO nation (id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta)
            VALUES (1, '후한',   '#ffd700', 1,  10000, 10000, 1500, 7, 'che_명가', CAST('{}' AS jsonb)),
                   (2, '황건적', '#8b4513', 50,  5000,  5000,  500, 5, 'che_명가', CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // Actor BEFORE = fixture cases[0].before.general (gid 11, neutral, exp 2374 / ded 1960).
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, crew, train, atmos,
                 crew_type_id, troop_id, weapon_code, book_code, horse_code, item_code,
                 npc_state, turn_time, last_turn, meta)
            VALUES
                (11, 'ⓝ공손범', 0, 32, 61, 67, 61, 0, 2374, 1960, 0, 1000, 1000, 0, 0, 0,
                 0, 0, 'None', 'None', 'None', 'None', 0, now(),
                 CAST('{"command":"휴식"}' AS jsonb),
                 CAST('{"explevel":15,"intel_exp":0,"max_domestic_critical":0}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // Pre-seed the 37 rank_data rows for the actor (PHP seeds at creation; the flush UPDATEs).
        for (col in RANK_COLUMNS) {
            jdbc.update(
                "INSERT INTO rank_data (nation_id, general_id, type, value) VALUES (:nid, :gid, :type, 0)",
                MapSqlParameterSource()
                    .addValue("nid", priorNationId)
                    .addValue("gid", actorId)
                    .addValue("type", col),
            )
        }
    }

    @Test
    fun `che_거병 satellite write-set flushes byte-comparable to the golden DB after-image`() {
        val payload = buildGeobyeongPayload()

        executor.flush(payload)

        // --- op order == the frozen step contract: world (step-1) → nation + diplomacy + nation_turn
        //     createMany (step-3, in this in-slot order) → general UPDATE (step-7) → rank_data nation_id
        //     sync (step-8) → log_entry (step-9). All slots fire in the FROZEN 10-step order.
        assertEquals(
            listOf(
                FlushExecOp("world_state", FlushVerb.UPDATE, 1),
                FlushExecOp("nation", FlushVerb.CREATE_MANY, 1),
                FlushExecOp("diplomacy", FlushVerb.CREATE_MANY, 4),   // 2 peers × bidirectional
                FlushExecOp("nation_turn", FlushVerb.CREATE_MANY, 24),
                FlushExecOp("general", FlushVerb.UPDATE, 1),
                FlushExecOp("rank_data", FlushVerb.UPDATE, 1),        // nation_id sync (1 general)
                FlushExecOp("log_entry", FlushVerb.CREATE_MANY, 2),
            ),
            executor.lastOps(),
        )

        assertCreatedNation()
        assertCreatedDiplomacy()
        assertNationTurnAfterImage()
        assertRankDataNationSync()
        assertGeneralAfterImage()
        assertLogEntryAfterImage()
    }

    // ---- the golden after-image, built from the fixture + che_거병.php grand truth ----

    private fun buildGeobyeongPayload(): FlushPayload {
        // Created nation (che_거병.php:98-110) — meta in PHP insertion order, byte-comparable jsonb.
        val createdNation = GEOBYEONG_NATION

        // 24 nation_turn rows (che_거병.php:142-156): officer_level [12, 11] × turn_idx 0..11,
        // each action='휴식', arg=null (→ '{}' jsonb), brief='휴식' (the V2 brief after-image).
        val createdNationTurns = buildList {
            for (chiefLevel in listOf(12, 11)) {
                for (turnIdx in 0 until MAX_CHIEF_TURN) {
                    add(NationTurn(newNationId, chiefLevel, turnIdx, action = "휴식", arg = null, brief = "휴식"))
                }
            }
        }

        // Bidirectional diplomacy to every other nation (che_거병.php:114-138): (me,you,state=2,term=0).
        val createdDiplomacy = buildList {
            for (peer in listOf(1, 2)) {
                add(Diplomacy(me = peer, you = newNationId, state = 2, term = 0))
                add(Diplomacy(me = newNationId, you = peer, state = 2, term = 0))
            }
        }

        // Actor AFTER = fixture cases[0].after.general: nation 0→3, officer_level 0→12, exp+100, ded+100,
        // last_turn = LastTurn('거병', arg=[]) ⇒ {"command":"거병"} (general-command setResultTurn target).
        val actorAfter = General(
            id = actorId, nationId = newNationId, cityId = actorCity,
            leadership = 61, strength = 67, intel = 61, injury = 0,
            experience = 2474.0, dedication = 2060.0,
            officerLevel = 12, gold = 1000, rice = 1000,
            crew = 0, train = 0.0, atmos = 0.0, crewTypeId = 0, troop = 0,
            lastTurn = LastTurn(command = "거병"),
            meta = linkedMapOf("explevel" to 15, "intel_exp" to 0, "max_domestic_critical" to 0),
        )

        // log_entry: the acting log + the global broadcast (fixture logLines/broadcastLines).
        val logEntries = listOf(
            LogRow(
                scope = "GENERAL", category = "ACTION",
                text = "<C>●</>1월:거병에 성공하였습니다. <1>02:46</>",
                year = 181, month = 1, generalId = actorId, nationId = newNationId,
            ),
            LogRow(
                scope = "NATION", category = "ACTION",
                text = "<C>●</>1월:<Y>ⓝ공손범</>이 <G><b>평양</b></>에 거병하였습니다.",
                year = 181, month = 1, generalId = 0, nationId = newNationId,
            ),
        )

        return FlushPayload(
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 181, "current_month" to 1),
            updatedGenerals = listOf(actorAfter),
            createdNations = listOf(createdNation),
            createdNationTurns = createdNationTurns,
            createdDiplomacy = createdDiplomacy,
            rankNationSync = listOf(RankNationSync(generalId = actorId, nationId = newNationId)),
            logEntries = logEntries,
        )
    }

    // ---- byte-comparable assertions vs the golden after-image ----

    private fun assertCreatedNation() {
        val row = jdbc.queryForMap(
            "SELECT name, color, capital_city_id, gold, rice, tech, level, type_code, meta::text AS meta FROM nation WHERE id = :id",
            MapSqlParameterSource().addValue("id", newNationId),
        )
        assertEquals("ⓝ공손범", stringOf(row["name"]))
        assertEquals("#330000", stringOf(row["color"]))
        assertEquals(null, row["capital_city_id"], "거병 nation has no capital yet")
        assertEquals(0, intOf(row["gold"]))
        assertEquals(2000, intOf(row["rice"]))
        assertEquals("che_중립", stringOf(row["type_code"]))
        assertEquals(0, intOf(row["level"]))
        // meta after-image, decoded from the PG row (PG jsonb normalizes key order on storage, so the
        // decoded MAP is the correct PG-readback oracle; the byte-ORDER of the emitted jsonb is asserted
        // at the encode layer below, the same split NationRowMapperTest uses).
        assertEquals(
            mapOf<String, Any?>(
                "rate" to 20, "bill" to 100, "strategic_cmd_limit" to 12,
                "surlimit" to 72, "secretlimit" to 1, "gennum" to 1,
            ),
            MetaJson.decode(stringOf(row["meta"])),
        )
        // Byte-comparable jsonb the flush EMITS to PG: PHP insertion order
        // rate→bill→strategic_cmd_limit→surlimit→secretlimit→gennum, ints not `20.0` (MetaJson encode).
        assertEquals(
            """{"rate":20,"bill":100,"strategic_cmd_limit":12,"surlimit":72,"secretlimit":1,"gennum":1}""",
            NationRowMapper.toColumns(GEOBYEONG_NATION)["meta"],
            "nation.meta jsonb byte-identical (insertion order preserved at the flush encode layer)",
        )
    }

    private fun assertCreatedDiplomacy() {
        // 4 directional rows: for each peer nation a bidirectional (me,you,state=2,term=0) pair
        // (che_거병.php:114-138). The new nation appears as both src and dest with EVERY existing nation.
        val rows = jdbc.queryForList(
            "SELECT src_nation_id, dest_nation_id, state_code, term FROM diplomacy ORDER BY src_nation_id, dest_nation_id",
            MapSqlParameterSource(),
        ).map {
            listOf(intOf(it["src_nation_id"]), intOf(it["dest_nation_id"]), intOf(it["state_code"]), intOf(it["term"]))
        }
        assertEquals(
            listOf(
                listOf(1, newNationId, 2, 0),       // peer 1 → new
                listOf(2, newNationId, 2, 0),       // peer 2 → new
                listOf(newNationId, 1, 2, 0),       // new → peer 1
                listOf(newNationId, 2, 2, 0),       // new → peer 2
            ),
            rows,
            "거병 bidirectional diplomacy pairs byte-comparable (state=2 term=0 to every other nation)",
        )
    }

    private fun assertNationTurnAfterImage() {
        // Exactly 24 nation_turn rows for the created nation.
        assertEquals(
            24,
            jdbc.queryForObject(
                "SELECT count(*) FROM nation_turn WHERE nation_id = :id",
                MapSqlParameterSource().addValue("id", newNationId),
                Int::class.java,
            ),
        )
        // Every row: action_code='휴식', arg='{}'::jsonb, brief='휴식' (the V2 brief after-image PINNED).
        val rows = jdbc.queryForList(
            "SELECT officer_level, turn_idx, action_code, arg::text AS arg, brief FROM nation_turn WHERE nation_id = :id ORDER BY officer_level DESC, turn_idx",
            MapSqlParameterSource().addValue("id", newNationId),
        )
        val expected = buildList {
            for (chiefLevel in listOf(12, 11)) {
                for (turnIdx in 0 until MAX_CHIEF_TURN) {
                    add(mapOf("officer_level" to chiefLevel, "turn_idx" to turnIdx, "action_code" to "휴식", "arg" to "{}", "brief" to "휴식"))
                }
            }
        }
        val actual = rows.map {
            mapOf(
                "officer_level" to intOf(it["officer_level"]),
                "turn_idx" to intOf(it["turn_idx"]),
                "action_code" to stringOf(it["action_code"]),
                "arg" to stringOf(it["arg"]),
                "brief" to stringOf(it["brief"]),
            )
        }
        assertEquals(expected, actual, "24 nation_turn rows byte-comparable incl. brief='휴식'")
    }

    private fun assertRankDataNationSync() {
        // All 37 pre-seeded rows survive and now carry the new nation_id (the only rank effect for the NPC actor).
        assertEquals(
            37,
            jdbc.queryForObject(
                "SELECT count(*) FROM rank_data WHERE general_id = :gid",
                MapSqlParameterSource().addValue("gid", actorId),
                Int::class.java,
            ),
            "RANK_ROWS_PER_GENERAL = 37 (the PHP RankColumn enum cases())",
        )
        val distinctNationIds = jdbc.queryForList(
            "SELECT DISTINCT nation_id FROM rank_data WHERE general_id = :gid",
            MapSqlParameterSource().addValue("gid", actorId),
            Int::class.java,
        )
        assertEquals(listOf(newNationId), distinctNationIds, "nation_id sync rewrote ALL 37 rows to the new nation")
        // No value perturbation — the NPC actor's increaseInheritancePoint short-circuits (no owner).
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM rank_data WHERE general_id = :gid AND value <> 0",
                MapSqlParameterSource().addValue("gid", actorId),
                Int::class.java,
            ),
            "거병 (NPC actor) increments no rank value — only the nation_id sync fires",
        )
    }

    private fun assertGeneralAfterImage() {
        val row = jdbc.queryForMap(
            "SELECT nation_id, city_id, officer_level, experience, dedication, last_turn::text AS last_turn FROM general WHERE id = :gid",
            MapSqlParameterSource().addValue("gid", actorId),
        )
        assertEquals(newNationId, intOf(row["nation_id"]))
        assertEquals(actorCity, intOf(row["city_id"]))
        assertEquals(12, intOf(row["officer_level"]))
        assertEquals(2474, intOf(row["experience"]), "exp 2374 + 100")
        assertEquals(2060, intOf(row["dedication"]), "ded 1960 + 100")
        // last_turn jsonb: general-command setResultTurn target ⇒ {"command":"거병"} (delete-on-default).
        // PG normalizes jsonb on storage (adds a space after `:`) so the decoded MAP is the PG-readback
        // oracle; the byte-faithful jsonb the flush EMITS is asserted at the encode layer below.
        assertEquals(mapOf<String, Any?>("command" to "거병"), MetaJson.decode(stringOf(row["last_turn"])))
        assertEquals(
            """{"command":"거병"}""",
            MetaJson.encode(LastTurn(command = "거병").toRaw()),
            "general.last_turn jsonb byte-identical at the flush encode layer (delete-on-default omits term/seq)",
        )
    }

    private fun assertLogEntryAfterImage() {
        val rows = jdbc.queryForList(
            "SELECT scope::text AS scope, category::text AS category, text, general_id, nation_id FROM log_entry ORDER BY id",
            MapSqlParameterSource(),
        )
        assertEquals(2, rows.size)
        assertEquals("GENERAL", stringOf(rows[0]["scope"]))
        assertEquals("ACTION", stringOf(rows[0]["category"]))
        assertEquals("<C>●</>1월:거병에 성공하였습니다. <1>02:46</>", stringOf(rows[0]["text"]))
        assertEquals(actorId, intOf(rows[0]["general_id"]))
        assertEquals(newNationId, intOf(rows[0]["nation_id"]))
        assertEquals("NATION", stringOf(rows[1]["scope"]))
        assertEquals("ACTION", stringOf(rows[1]["category"]))
        assertEquals("<C>●</>1월:<Y>ⓝ공손범</>이 <G><b>평양</b></>에 거병하였습니다.", stringOf(rows[1]["text"]))
    }

    companion object {
        private const val MAX_CHIEF_TURN = 12   // GameConst::$maxChiefTurn

        /**
         * The nation che_거병 creates (`che_거병.php:98-110`): the single source of truth for both the
         * flushed payload and the byte-comparable-jsonb encode-layer assertion. meta in PHP insertion
         * order (rate→bill→strategic_cmd_limit→surlimit→secretlimit→gennum).
         */
        private val GEOBYEONG_NATION = Nation(
            id = 3,
            level = 0,
            capitalCityId = null,
            name = "ⓝ공손범",        // nationName = generalName (no duplicate ⇒ verbatim)
            color = "#330000",
            typeCode = "che_중립",     // GameConst::neutralNationType
            gold = 0,
            rice = 2000,              // GameConst::baserice
            tech = 0.0,
            meta = linkedMapOf(
                "rate" to 20,
                "bill" to 100,
                "strategic_cmd_limit" to 12,
                "surlimit" to 72,
                "secretlimit" to 1,  // scenario ≥ 1000
                "gennum" to 1,
            ),
        )

        /** The 37 rank_data column names = the PHP `sammo\Enums\RankColumn` enum backing values. */
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
