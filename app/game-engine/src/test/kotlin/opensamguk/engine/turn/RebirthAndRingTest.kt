package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.ServerClock
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B4 Task LC3 — rebirth() in-place UPDATE + pull*Command ring shift.
 *
 * Port target: `General.php:602-639` (rebirth, the age>=retirementYear branch of updateTurnTime
 * `TurnExecutionHelper.php:209-216`, NPCType==0 human only) + `func_command.php:56-79`
 * (pullGeneralCommand) / `:140-169` (pullNationCommand).
 *
 * rebirth = an in-place UPDATE (the OPPOSITE of kill — the general row stays, all 37 rank_data rows
 * reset to 0); the pull*Command rings rotate the run slot (turn_idx 0) to the tail as 휴식 and shift
 * the rest down one. The ring-shift section uses a Testcontainers postgres (the real JDBC repo).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RebirthAndRingTest {

    private val t0 = Instant.parse("0200-06-15T14:00:00Z")
    private val turnTerm = 120

    private fun env(isunited: Int = 0) = LifecycleEnv(
        baselineKillturn = 12, year = 200, month = 6, turnTerm = turnTerm, isunited = isunited,
    )

    private fun gen(
        id: Int = 1,
        npc: Int = 0,
        age: Int = 80,
        killturn: Int = 12,
        leadership: Int = 80,
        strength: Int = 70,
        intel: Int = 60,
        experience: Int = 1000,
        dedication: Int = 2000,
        extraMeta: Map<String, Any?> = emptyMap(),
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(leadership, strength, intel),
        experience = experience,
        dedication = dedication,
        officerLevel = 1,
        age = age,
        npcState = npc,
        injury = 50,
        turnTime = t0,
        meta = linkedMapOf<String, Any?>(
            "killturn" to killturn,
            "deadyear" to 999,
            "lived_month" to 100,
            "specage" to 30,
            "specage2" to 40,
            "dex1" to 1000, "dex2" to 2000, "dex3" to 3000, "dex4" to 4000, "dex5" to 5000,
        ) + extraMeta,
    )

    private fun world(g: TurnGeneral) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(1, 200, 6, 3600, t0),
            generals = listOf(g),
            nations = listOf(Nation(1, "n1", "#000")),
        ),
    )

    private fun handler(world: InMemoryTurnWorld) = ReservedTurnHandler(
        world,
        registry = CommandRegistry(GeneralActionPipeline()),
        hiddenSeed = "0".repeat(32),
        startYear = 184,
    )

    // ── rebirth() in-place UPDATE (General.php:602-639) ──────────────────────────────────────────

    @Test
    fun `age 80 human isunited 0 rebirths in place - stats x085 floor10, age20, no delete`() {
        val g = gen(age = 80, leadership = 80, strength = 70, intel = 60, experience = 1000, dedication = 2000)
        val w = world(g)
        val h = handler(w)

        val outcome = h.updateTurnTime(1, env(isunited = 0))
        assertEquals(LifecycleOutcome.REBIRTHED, outcome)

        val after = w.getGeneralById(1)!!
        // ×0.85 (phpRound half-away), floor 10.
        assertEquals(68, after.stats.leadership) // round(80*0.85=68.0)
        assertEquals(60, after.stats.strength)   // round(70*0.85=59.5) -> 60 (half away)
        assertEquals(51, after.stats.intelligence) // round(60*0.85=51.0)
        assertEquals(0, after.injury)
        assertEquals(500, after.experience)      // 1000*0.5
        assertEquals(1000, after.dedication)     // 2000*0.5
        assertEquals(20, after.age)
        assertEquals(0, (after.meta["specage"] as Number).toInt())
        assertEquals(0, (after.meta["specage2"] as Number).toInt())
        assertEquals(500, (after.meta["dex1"] as Number).toInt())   // 1000*0.5
        assertEquals(2500, (after.meta["dex5"] as Number).toInt())  // 5000*0.5

        // in-place — the row is NOT tombstoned.
        assertFalse(w.consumeDirtyState().deletedGenerals.contains(1), "rebirth does NOT delete")
        assertNotNull(w.getGeneralById(1), "the general row persists after rebirth")
    }

    @Test
    fun `stat floor 10 applies when x085 would drop below 10`() {
        val g = gen(age = 85, leadership = 11, strength = 11, intel = 11)
        val w = world(g)
        handler(w).updateTurnTime(1, env())
        val after = w.getGeneralById(1)!!
        // 11*0.85 = 9.35 -> round 9 -> floored to 10.
        assertEquals(10, after.stats.leadership)
        assertEquals(10, after.stats.strength)
        assertEquals(10, after.stats.intelligence)
    }

    @Test
    fun `rebirth resets all 37 rank_data rows to 0 via setRankVar`() {
        val g = gen(age = 80)
        val w = world(g)
        val h = handler(w)
        h.updateTurnTime(1, env())
        val deltas = h.recorder.rankDeltas(1)
        assertEquals(RankColumn.entries.size, deltas.size, "all 37 rank columns recorded")
        assertTrue(deltas.values.all { it == RankDelta.Set(0) }, "every rank delta is a Set(0)")
    }

    @Test
    fun `rebirth pushes the THREE distinct log lines in order (global, general PLAIN, history)`() {
        val g = gen(id = 1, age = 80)
        val w = world(g)
        handler(w).updateTurnTime(1, env())
        val logs = w.consumeDirtyState().logs
        // global 은퇴 (josa pick(g1,'이') -> 이).
        assertTrue(
            logs.any {
                it.scope == "global" &&
                    it.text == "<Y>g1</>이 <R>은퇴</>하고 그 자손이 유지를 이어받았습니다."
            },
            "global 은퇴 log byte-exact",
        )
        // general action PLAIN.
        assertTrue(
            logs.any {
                it.scope == "general" && it.category == "action" &&
                    it.text == "나이가 들어 <R>은퇴</>하고 자손에게 자리를 물려줍니다."
            },
            "general action 은퇴 log byte-exact",
        )
        // general history.
        assertTrue(
            logs.any {
                it.scope == "general" && it.category == "history" &&
                    it.text == "나이가 들어 은퇴하고, 자손에게 관직을 물려줌"
            },
            "general history 은퇴 log byte-exact",
        )
    }

    @Test
    fun `rebirth still advances turntime by addTurn (the general row stays)`() {
        val g = gen(age = 80)
        val w = world(g)
        handler(w).updateTurnTime(1, env())
        assertEquals(ServerClock.addTurn(t0, turnTerm, 1), w.getGeneralById(1)!!.turnTime)
    }

    @Test
    fun `rebirth does NOT fire when isunited is not 0`() {
        val g = gen(age = 90)
        val w = world(g)
        val outcome = handler(w).updateTurnTime(1, env(isunited = 1))
        assertEquals(LifecycleOutcome.SURVIVED, outcome, "no rebirth on a unified server")
        // stats untouched, turntime still advanced.
        assertEquals(80, w.getGeneralById(1)!!.stats.leadership)
        assertEquals(90, w.getGeneralById(1)!!.age)
    }

    @Test
    fun `rebirth does NOT fire for an NPC (npc != 0)`() {
        val g = gen(age = 80, npc = 2)
        val w = world(g)
        val outcome = handler(w).updateTurnTime(1, env())
        assertEquals(LifecycleOutcome.SURVIVED, outcome)
        assertEquals(80, w.getGeneralById(1)!!.age, "NPC never rebirths")
    }

    // ── pull*Command ring shift (func_command.php:56-79 / :140-169) — Testcontainers ─────────────

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repo: ReservedTurnRepository

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
        repo = ReservedTurnRepository(jdbc)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `pullGeneralTurn vacates slot 0 to 휴식 and shifts the remaining slots down one`() {
        repo.reserve(generalId = 7, turnIdx = 0, actionCode = "che_상업투자", argJson = """{"a":1}""", brief = "상업투자")
        repo.reserve(generalId = 7, turnIdx = 1, actionCode = "che_농지개간", argJson = """{"a":2}""", brief = "농지개간")
        repo.reserve(generalId = 7, turnIdx = 2, actionCode = "che_기술연구", argJson = """{"a":3}""", brief = "기술연구")

        repo.pullGeneralTurn(generalId = 7)

        // slots shift down one: slot0 <- 농지개간, slot1 <- 기술연구.
        assertEquals("che_농지개간", repo.readReserved(7, 0).actionCode)
        assertEquals("che_기술연구", repo.readReserved(7, 1).actionCode)
        // the run slot rotated to the ring tail (turn_idx 29) as 휴식/{}/휴식.
        val tail = repo.readReserved(7, 29)
        assertEquals("휴식", tail.actionCode)
        assertEquals("{}", tail.argJson)
        assertEquals("휴식", tail.brief)
    }

    @Test
    fun `pullNationTurn rotates the chief ring and vacates to 휴식`() {
        repo.reserveNationTurn(nationId = 9, officerLevel = 12, turnIdx = 0, actionCode = "che_증축", argJson = """{"a":1}""", brief = "증축")
        repo.reserveNationTurn(nationId = 9, officerLevel = 12, turnIdx = 1, actionCode = "che_감축", argJson = """{"a":2}""", brief = "감축")

        repo.pullNationTurn(nationId = 9, officerLevel = 12)

        assertEquals("che_감축", repo.readReservedNationTurn(9, 12, 0).actionCode)
        val tail = repo.readReservedNationTurn(9, 12, 11)
        assertEquals("휴식", tail.actionCode)
        assertEquals("{}", tail.argJson)
    }

    @Test
    fun `pullGeneralTurn is a no-op for turnCnt 0 or turnCnt at the ring length`() {
        repo.reserve(generalId = 8, turnIdx = 0, actionCode = "che_상업투자", brief = "상업투자")
        repo.pullGeneralTurn(generalId = 8, turnCnt = 0)
        repo.pullGeneralTurn(generalId = 8, turnCnt = ReservedTurnRepository.MAX_GENERAL_TURNS)
        assertEquals("che_상업투자", repo.readReserved(8, 0).actionCode, "guards leave the ring untouched")
    }
}
