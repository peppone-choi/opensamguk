package opensamguk.infra.persistence

import opensamguk.infra.read.DiplomacyLetterRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W5d 외교 서신 채널 Testcontainers IT: [JdbcFlushExecutor]의 diplomacy_letter INSERT(발송, id 선할당) +
 * UPDATE(회수/파기/대체)가 실제 V1 baseline `diplomacy_letter` 테이블(+ `diplomacy_letter_state` enum)에
 * 대해 컬럼/캐스트가 올바른지 증명하고, [DiplomacyLetterRepository] read seam의 SELECT(findLetter /
 * countNewerLetters)가 enum 소문자 정규화 + aux/state_opt 추출을 정확히 수행하는지 검증한다.
 *
 * BoardFlushIT를 미러링한다. 이 IT는 컨트롤러를 직접 띄우지 않고 컨트롤러 인테이크가 수렴하는
 * recorder→FlushPayload→executor 경로(쓰기) + read seam(읽기)을 실 DB로 닫는다(컨트롤러는
 * CommandController 공용 인테이크를 그대로 타므로 별도 라우팅 IT가 불필요 — 다른 인테이크 슬라이스와 동일).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiplomaticLetterControllerIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private lateinit var letterRepo: DiplomacyLetterRepository

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1)

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        letterRepo = DiplomacyLetterRepository(jdbc)
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    // PER_CLASS 공유 컨테이너 — 메서드 간 격리(다른 테스트의 INSERT가 count 단언에 새지 않도록).
    @org.junit.jupiter.api.BeforeEach
    fun cleanEach() {
        jdbc.update("TRUNCATE diplomacy_letter, message RESTART IDENTITY CASCADE", MapSqlParameterSource())
    }

    @Test
    fun `diplomacy_letter INSERT(발송) persists with PROPOSED enum + aux, and read seam normalizes to lowercase`() {
        val auxJson = """{"src":{"nationName":"촉","nationColor":"#00ff00","generalName":"유비","generalIcon":""},"dest":{"nationName":"위","nationColor":"#0000ff"}}"""
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterInserts = listOf(
                    DiplomacyLetterInsertRow(
                        id = 500,
                        columns = linkedMapOf(
                            "src_nation_id" to 1, "dest_nation_id" to 2, "prev_id" to null,
                            "state" to "PROPOSED", "text_brief" to "동맹 제의", "text_detail" to "함께 합시다",
                            "date" to "0200-01-01T00:00:00Z", "src_signer" to 10, "dest_signer" to null,
                            "aux" to auxJson,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, count("diplomacy_letter"))
        assertTrue(executor.lastOps().any { it.table == "diplomacy_letter" && it.verb == FlushVerb.CREATE_MANY })

        // read seam: enum 대문자 'PROPOSED' → 소문자 'proposed' 정규화, aux 보존, state_opt 없음.
        val row = letterRepo.findLetter(500)!!
        assertEquals(500, row.letterNo)
        assertEquals(1, row.srcNationId)
        assertEquals(2, row.destNationId)
        assertNull(row.prevNo)
        assertEquals("proposed", row.state)
        assertNull(row.stateOpt)
        // aux는 jsonb — 키 정규화/공백(`"key": "val"`)이 적용되므로 값 존재로 라운드트립을 검증.
        assertTrue(row.auxJson.contains("nationName") && row.auxJson.contains("촉"))
    }

    @Test
    fun `prev letter UPDATE(replaced) + new INSERT, then countNewerLetters sees the chain`() {
        // prev #600 발송.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterInserts = listOf(
                    DiplomacyLetterInsertRow(
                        id = 600,
                        columns = baseCols(src = 1, dest = 2, prevId = null, state = "PROPOSED"),
                    ),
                ),
            ),
        )
        // 대체: #600 → REPLACED + new #601(prev_id=600) INSERT.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterInserts = listOf(
                    DiplomacyLetterInsertRow(id = 601, columns = baseCols(src = 1, dest = 2, prevId = 600, state = "PROPOSED")),
                ),
                diplomacyLetterUpdates = linkedMapOf(
                    600 to linkedMapOf<String, Any?>(
                        "state" to "REPLACED",
                        "aux" to """{"src":{},"dest":{},"reason":{"who":10,"action":"new_letter","reason":"new_letter"}}""",
                    ),
                ),
            ),
        )
        assertEquals("replaced", letterRepo.findLetter(600)!!.state)
        assertEquals(600, letterRepo.findLetter(601)!!.prevNo)
        // countNewerLetters(600): prev_id=600 AND state != CANCELLED 인 #601 1건.
        assertEquals(1, letterRepo.countNewerLetters(600))

        // #601을 CANCELLED로 만들면 newer-count는 0.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterUpdates = linkedMapOf(601 to linkedMapOf<String, Any?>("state" to "CANCELLED")),
            ),
        )
        assertEquals(0, letterRepo.countNewerLetters(600))
        assertTrue(executor.lastOps().any { it.table == "diplomacy_letter" && it.verb == FlushVerb.UPDATE })
    }

    @Test
    fun `destroy two-phase - state_opt set on aux then CANCELLED, read seam extracts state_opt`() {
        // activated 서신 #700.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterInserts = listOf(
                    DiplomacyLetterInsertRow(id = 700, columns = baseCols(src = 1, dest = 2, prevId = null, state = "ACTIVATED")),
                ),
            ),
        )
        // 1단계: aux에 state_opt try_destroy_src 적재(상태는 activated 유지).
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterUpdates = linkedMapOf(
                    700 to linkedMapOf<String, Any?>(
                        "aux" to """{"src":{},"dest":{},"state_opt":"try_destroy_src"}""",
                    ),
                ),
            ),
        )
        val phase1 = letterRepo.findLetter(700)!!
        assertEquals("activated", phase1.state)
        assertEquals("try_destroy_src", phase1.stateOpt)

        // 2단계: cancelled.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                diplomacyLetterUpdates = linkedMapOf(700 to linkedMapOf<String, Any?>("state" to "CANCELLED")),
            ),
        )
        assertEquals("cancelled", letterRepo.findLetter(700)!!.state)
    }

    @Test
    fun `diplomacy message INSERT persists with diplomacy enum type`() {
        // 외교 서신 발송이 함께 내보내는 diplomacy 메시지(국가 mailbox 양측)도 실제 message 테이블에 INSERT된다.
        executor.flush(
            testFlushPayload(
                worldId = opensamguk.common.world.WorldId(1),
                worldStateUpdate = ws(),
                createdMessages = listOf(
                    CreatedMessageRow(
                        id = 9001, mailbox = 9002, type = "diplomacy", srcId = 9001, destId = 9002,
                        time = "0200-01-01T00:00:00Z", validUntil = "9999-12-31",
                        bodyJson = """{"src":{"id":10},"dest":{"id":0},"text":"새로운 외교 문서 #500이 준비되었습니다. 외교부에서 확인해주세요.","option":{"deletable":false}}""",
                    ),
                ),
            ),
        )
        val type = jdbc.queryForObject(
            "SELECT type::text FROM message WHERE id = :id",
            MapSqlParameterSource("id", 9001), String::class.java,
        )
        assertEquals("diplomacy", type)
    }

    private fun baseCols(src: Int, dest: Int, prevId: Int?, state: String): Map<String, Any?> = linkedMapOf(
        "src_nation_id" to src, "dest_nation_id" to dest, "prev_id" to prevId, "state" to state,
        "text_brief" to "brief", "text_detail" to "detail", "date" to "0200-01-01T00:00:00Z",
        "src_signer" to 10, "dest_signer" to null,
        "aux" to """{"src":{},"dest":{}}""",
    )

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", MapSqlParameterSource(), Int::class.java)!!
}
