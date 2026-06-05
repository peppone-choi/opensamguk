package opensamguk.gameapi.read

import opensamguk.infra.read.BettingRepository
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
 * W3 — `ng_betting` 집계 read 경로 IT. PHP `GetBettingDetail.php:61-76`의
 * `SELECT betting_type, sum(amount) ... GROUP BY betting_type`가 정확히 SUM/그룹핑됨을 증명한다.
 *
 * `W3FoundationReadIT`/`ReadRepositoryIT`와 동일한 Testcontainers `postgres:16-alpine` + `:infra`
 * Flyway(V7 `ng_betting`) baseline(`ddl-auto: validate`). raw SQL로 행을 심고 레포 집계로 단언.
 */
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BettingAggregateReadIT {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var betting: BettingRepository

    private fun insertBet(bettingId: Int, generalId: Int, userId: Int?, type: String, amount: Int) {
        jdbc.update(
            "INSERT INTO ng_betting (betting_id, general_id, user_id, betting_type, amount) " +
                "VALUES (?, ?, ?, ?, ?)",
            bettingId, generalId, userId, type, amount,
        )
    }

    @Test
    fun `aggregateAmountByType sums per betting_type within a betting`() {
        // betting 3: type [0] = 100+200(서로 다른 장수) = 300, type [1] = 50.
        insertBet(3, 10, 100, "[0]", 100)
        insertBet(3, 11, 101, "[0]", 200)
        insertBet(3, 12, 102, "[1]", 50)
        // 다른 betting(4)은 섞이면 안 됨.
        insertBet(4, 13, 103, "[0]", 9999)

        val agg = betting.aggregateAmountByType(3).associate { it.bettingType to it.sumAmount }
        assertEquals(2, agg.size)
        assertEquals(300L, agg["[0]"])
        assertEquals(50L, agg["[1]"])

        // 베팅 없는 id → 빈 집계.
        assertEquals(0, betting.aggregateAmountByType(999).size)
    }

    @Test
    fun `aggregateAmountByTypeForUser restricts to a single user`() {
        insertBet(5, 20, 200, "[0]", 100)
        insertBet(5, 21, 200, "[0]", 250) // 같은 user 200, 합산되어야
        insertBet(5, 22, 201, "[0]", 999) // 다른 user → 제외

        val mine = betting.aggregateAmountByTypeForUser(5, 200).associate { it.bettingType to it.sumAmount }
        assertEquals(1, mine.size)
        assertEquals(350L, mine["[0]"])
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
