package opensamguk.infra.read

import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
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

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuctionRepositoryIT {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var auctions: AuctionRepository

    @Test
    fun `auction enum value queries run against Postgres enum columns`() {
        jdbc.update(
            """
            INSERT INTO ng_auction (
                type, finished, target, host_general_id, req_resource, open_date, close_date, detail
            ) VALUES (
                'buyRice'::ng_auction_type, false, null, 10, 'gold'::ng_auction_resource,
                TIMESTAMPTZ '2026-06-19 00:00:00+00',
                TIMESTAMPTZ '2026-06-20 00:00:00+00',
                '{"hostName":"테스트","amount":100,"startBidAmount":10}'::jsonb
            )
            """.trimIndent(),
        )

        val active = auctions.findByFinishedFalseAndTypeValue(AuctionType.BUY_RICE.value)
        val byType = auctions.findByTypeValueOrderByCloseDateAsc(AuctionType.BUY_RICE.value)

        assertEquals(1, active.size)
        assertEquals(AuctionType.BUY_RICE, active.single().type)
        assertEquals(ResourceType.GOLD, active.single().reqResource)
        assertEquals(active.single().id, byType.single().id)
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
