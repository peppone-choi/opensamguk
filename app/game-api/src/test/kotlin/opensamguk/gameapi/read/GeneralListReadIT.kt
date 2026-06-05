package opensamguk.gameapi.read

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

/**
 * W3 GeneralList — 신규 repo 쿼리(`findByNationIdOrderByTurnTimeAsc` 정렬 + general_turn 일괄
 * `findReservedByGeneralIds`)가 V1/V2 baseline에서 정확히 동작함을 증명한다.
 *
 * `W3FoundationReadIT`와 동일한 Testcontainers `postgres:16-alpine` + `:infra` Flyway baseline
 * (`ddl-auto: validate`) 패턴. raw SQL로 행을 심고 read 레포로 로드해 단언한다.
 */
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GeneralListReadIT {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var generalTurns: GeneralTurnReadRepository

    private fun insertGeneral(id: Int, nationId: Int, npc: Int, turnTime: String, recentWar: String? = null, age: Int = 30) {
        jdbc.update(
            """
            INSERT INTO general (
                id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice,
                crew, crew_type_id, train, atmos, troop_id,
                weapon_code, book_code, horse_code, item_code, npc_state,
                special_code, special2_code, personal_code, penalty, officer_city,
                turn_time, recent_war_time, age, last_turn, meta
            ) VALUES (
                $id, 'g$id', $nationId, 5, 50, 50, 50, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 0, 0,
                'None', 'None', 'None', 'None', $npc,
                'None', 'None', 'None', '{}'::jsonb, 0,
                TIMESTAMPTZ '$turnTime', ${if (recentWar == null) "NULL" else "TIMESTAMPTZ '$recentWar'"}, $age,
                '{}'::jsonb, '{}'::jsonb
            )
            """.trimIndent(),
        )
    }

    private fun insertTurn(generalId: Int, turnIdx: Int, action: String, brief: String) {
        jdbc.update(
            "INSERT INTO general_turn (general_id, turn_idx, action_code, arg, brief) VALUES (?, ?, ?, '{}'::jsonb, ?)",
            generalId, turnIdx, action, brief,
        )
    }

    @Test
    fun `findByNationIdOrderByTurnTimeAsc 는 turn_time 오름차순으로 반환한다`() {
        // 같은 국가(1)에 3명 — turn_time이 뒤섞인 순서로 insert.
        insertGeneral(id = 10, nationId = 1, npc = 0, turnTime = "2026-06-03 10:00:00+00")
        insertGeneral(id = 11, nationId = 1, npc = 0, turnTime = "2026-06-03 08:00:00+00")
        insertGeneral(id = 12, nationId = 1, npc = 0, turnTime = "2026-06-03 09:00:00+00")
        // 다른 국가(2) 1명은 결과에서 제외돼야 한다.
        insertGeneral(id = 20, nationId = 2, npc = 0, turnTime = "2026-06-03 07:00:00+00")

        val rows = generals.findByNationIdOrderByTurnTimeAsc(1)
        assertEquals(listOf(11, 12, 10), rows.map { it.id }) // 08:00 → 09:00 → 10:00
        // 다른 국가는 빠진다.
        assertEquals(setOf(1), rows.map { it.nationId }.toSet())

        // recent_war_time / age 컬럼 materialize 확인(P1 recent_war / P0 age 원천).
        insertGeneral(id = 13, nationId = 1, npc = 0, turnTime = "2026-06-03 11:00:00+00", recentWar = "2026-06-02 22:15:30+00", age = 41)
        val g13 = generals.findById(13).orElseThrow()
        assertEquals(41, g13.age)
        assertEquals("2026-06-02 22:15:30", TurnTimeFormatter.full(g13.recentWarTime))
    }

    @Test
    fun `findReservedByGeneralIds 는 turn_idx 5 미만만 general별 정렬해 일괄 반환한다`() {
        insertGeneral(id = 30, nationId = 1, npc = 0, turnTime = "2026-06-03 10:00:00+00")
        insertGeneral(id = 31, nationId = 1, npc = 0, turnTime = "2026-06-03 10:00:00+00")
        // 30: 슬롯 0,1,2 + turn_idx 5(범위 밖, 제외돼야 함)
        insertTurn(30, 0, "출병", "출병")
        insertTurn(30, 1, "휴식", "휴식")
        insertTurn(30, 2, "집합", "집합")
        insertTurn(30, 5, "내정", "내정") // turn_idx >= 5 → 제외
        // 31: 슬롯 0
        insertTurn(31, 0, "모병", "모병")

        val rows = generalTurns.findReservedByGeneralIds(listOf(30, 31))
        // turn_idx 5는 빠지고 5건만(30: 0,1,2 / 31: 0).
        assertEquals(4, rows.size)
        // general별 asc 정렬.
        val byGeneral = rows.groupBy { it.generalId }
        assertEquals(listOf(0, 1, 2), byGeneral.getValue(30).map { it.turnIdx })
        assertEquals(listOf("출병", "휴식", "집합"), byGeneral.getValue(30).map { it.actionCode })
        assertEquals(listOf(0), byGeneral.getValue(31).map { it.turnIdx })
        assertEquals("모병", byGeneral.getValue(31).single().brief)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
