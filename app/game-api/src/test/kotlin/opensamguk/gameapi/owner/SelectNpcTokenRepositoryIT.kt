package opensamguk.gameapi.owner

import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    WorldScopedGeneralOwnerRepository::class,
    WorldScopedSelectNpcTokenRepository::class,
)
class SelectNpcTokenRepositoryIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tokens: SelectNpcTokenRepository
    @Autowired lateinit var owners: GeneralOwnerRepository

    @BeforeEach
    fun seedProcessWorld() {
        seedWorld(PROCESS_WORLD_ID)
    }

    @Test
    fun `pick result writes to the jsonb token column`() {
        val saved = tokens.save(
            SelectNpcTokenEntity(
                ownerId = 7L,
                validUntil = Instant.parse("2026-06-02T00:01:00Z"),
                pickMoreFrom = Instant.parse("2000-01-01T01:00:00Z"),
                pickResult = linkedMapOf(
                    "10" to linkedMapOf("generalId" to 10, "name" to "여포"),
                    "__pickMoreSeconds" to 10,
                ),
                nonce = 1,
                createdAt = Instant.parse("2026-06-02T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-02T00:00:00Z"),
            ),
        )
        tokens.flush()
        val savedId = requireNotNull(saved.id)
        val storedType = jdbc.queryForObject(
            "select pg_typeof(pick_result)::text from select_npc_token where world_id = ? and id = ?",
            String::class.java,
            PROCESS_WORLD_ID,
            savedId,
        )
        val storedName = jdbc.queryForObject(
            "select pick_result -> '10' ->> 'name' from select_npc_token where world_id = ? and id = ?",
            String::class.java,
            PROCESS_WORLD_ID,
            savedId,
        )
        assertEquals("jsonb", storedType)
        assertEquals("여포", storedName)
        assertEquals("여포", (tokens.findById(savedId).orElseThrow().pickResult["10"] as Map<*, *>)["name"])
        assertEquals(
            PROCESS_WORLD_ID,
            jdbc.queryForObject(
                "select world_id from select_npc_token where world_id = ? and id = ?",
                Int::class.java,
                PROCESS_WORLD_ID,
                savedId,
            ),
        )
    }

    @Test
    fun `process world wrappers isolate the same local owner and ids across worlds`() {
        seedWorld(OTHER_WORLD_ID)
        val now = Instant.parse("2026-06-02T00:00:00Z")

        jdbc.update(
            "INSERT INTO general_owner (world_id, general_id, user_id, claimed_at) VALUES (?, 10, 7, ?)",
            OTHER_WORLD_ID,
            Timestamp.from(now),
        )
        owners.save(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = now))

        assertEquals(10L, owners.findByUserId(7L)?.generalId)
        assertTrue(owners.existsByGeneralId(10L))
        assertEquals(listOf(10L), owners.findAllByOrderByGeneralIdAsc().map { it.generalId })
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM general_owner WHERE general_id = 10", Int::class.java))

        insertToken(worldId = PROCESS_WORLD_ID, id = 190, ownerId = 7, validUntil = now.plusSeconds(60), marker = "process")
        insertToken(worldId = OTHER_WORLD_ID, id = 190, ownerId = 7, validUntil = now.plusSeconds(120), marker = "other")
        insertToken(worldId = PROCESS_WORLD_ID, id = 191, ownerId = 8, validUntil = now.plusSeconds(60), marker = "reserved")
        insertToken(worldId = OTHER_WORLD_ID, id = 192, ownerId = 9, validUntil = now.minusSeconds(1), marker = "expired-other")

        val active = tokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(7L, now)
        assertEquals("process", active?.pickResult?.get("marker"))
        assertEquals(listOf(191L), tokens.findValidOtherTokens(7L, now).mapNotNull { it.id })
        assertEquals("process", tokens.findById(190L).orElseThrow().pickResult["marker"])

        assertEquals(1, tokens.deleteOwnerOrExpired(7L, now))
        assertFalse(tokens.findById(190L).isPresent)
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM select_npc_token WHERE world_id = ?",
                Int::class.java,
                OTHER_WORLD_ID,
            ),
        )
    }

    @Test
    fun `owner claim request id round trips and scopes terminal cleanup`() {
        val now = Instant.parse("2026-06-02T00:00:00Z")
        owners.save(
            GeneralOwnerEntity(
                generalId = 12L,
                userId = 8L,
                claimedAt = now,
                claimRequestId = "req-claim-12",
            ),
        )

        assertEquals("req-claim-12", owners.findByUserId(8L)?.claimRequestId)
        assertEquals(0, owners.deleteByUserIdAndGeneralIdAndClaimRequestId(8L, 12L, "other-request"))
        assertEquals("req-claim-12", owners.findByUserId(8L)?.claimRequestId)
        assertEquals(1, owners.deleteByUserIdAndGeneralIdAndClaimRequestId(8L, 12L, "req-claim-12"))
        assertEquals(null, owners.findByUserId(8L))
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, ?, 1, 1, 60) ON CONFLICT (id) DO NOTHING",
            id,
            "world-$id",
        )
    }

    private fun insertToken(
        worldId: Int,
        id: Long,
        ownerId: Long,
        validUntil: Instant,
        marker: String,
    ) {
        jdbc.update(
            """
            INSERT INTO select_npc_token (
                world_id, id, owner_id, valid_until, pick_more_from, pick_result, nonce, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, jsonb_build_object('marker', ?), 1, ?, ?)
            """.trimIndent(),
            worldId,
            id,
            ownerId,
            Timestamp.from(validUntil),
            Timestamp.from(Instant.parse("2000-01-01T01:00:00Z")),
            marker,
            Timestamp.from(Instant.parse("2026-06-02T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-06-02T00:00:00Z")),
        )
    }

    companion object {
        private const val PROCESS_WORLD_ID = 17
        private const val OTHER_WORLD_ID = 23

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("opensamguk.world-id") { PROCESS_WORLD_ID.toString() }
        }
    }
}
