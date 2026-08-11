package opensamguk.gameapi.owner

import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import opensamguk.gameapi.read.GeneralReadRepository
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    GeneralReadRepository::class,
    JdbcGeneralOwnershipReadSource::class,
    WorldScopedGeneralOwnerRepository::class,
    WorldScopedSelectNpcTokenRepository::class,
)
class SelectNpcTokenRepositoryIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var ownershipBodies: GeneralOwnershipReadSource
    @Autowired lateinit var tokens: SelectNpcTokenRepository
    @Autowired lateinit var owners: GeneralOwnerRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager

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
    fun `owner cleanup matches the observed request or legacy reservation before deleting`() {
        val now = Instant.parse("2026-06-02T00:00:00Z")
        val current = GeneralOwnerEntity(
            generalId = 12L,
            userId = 8L,
            claimedAt = now,
            claimRequestId = "req-claim-12",
        )
        owners.save(current)

        assertEquals("req-claim-12", owners.findByUserId(8L)?.claimRequestId)
        assertEquals(
            0,
            owners.deleteIfUnchanged(
                GeneralOwnerEntity(12L, 8L, now, claimRequestId = "other-request"),
            ),
        )
        assertEquals(
            0,
            owners.deleteIfUnchanged(
                GeneralOwnerEntity(12L, 8L, now.plusSeconds(1), claimRequestId = "req-claim-12"),
            ),
        )
        assertEquals("req-claim-12", owners.findByUserId(8L)?.claimRequestId)
        assertEquals(1, owners.deleteIfUnchanged(current))
        assertEquals(null, owners.findByUserId(8L))

        val legacy = GeneralOwnerEntity(generalId = 13L, userId = 8L, claimedAt = now)
        owners.save(legacy)
        assertEquals(0, owners.deleteIfUnchanged(GeneralOwnerEntity(13L, 8L, now.plusSeconds(1))))
        assertEquals(1, owners.deleteIfUnchanged(legacy))
        assertEquals(null, owners.findByUserId(8L))
    }

    @Test
    fun `legacy cleanup can reclaim the same owner key in one transaction`() {
        val now = Instant.parse("2026-06-02T00:00:00Z")
        val legacy = GeneralOwnerEntity(generalId = 14L, userId = 9L, claimedAt = now)
        owners.save(legacy)

        val observed = requireNotNull(owners.findByUserId(9L))
        assertEquals(1, owners.deleteIfUnchanged(observed))

        owners.save(
            GeneralOwnerEntity(
                generalId = 14L,
                userId = 9L,
                claimedAt = now.plusSeconds(1),
                claimRequestId = "req-reclaimed-14",
            ),
        )

        val reinserted = owners.findByUserId(9L)
        assertEquals("req-reclaimed-14", reinserted?.claimRequestId)
        assertEquals(now.plusSeconds(1), reinserted?.claimedAt)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `classifier snapshots a candidate after the daemon commits activation in the enclosing transaction`() {
        val now = Instant.parse("2026-06-02T00:00:00Z")
        seedWorld(OTHER_WORLD_ID)
        insertGeneral(id = 15, worldId = PROCESS_WORLD_ID, userId = null, npcState = 2)
        insertGeneral(id = 15, worldId = OTHER_WORLD_ID, userId = "99", npcState = 0)
        jdbc.update(
            """
            INSERT INTO general_owner (world_id, general_id, user_id, claimed_at, claim_request_id)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            PROCESS_WORLD_ID,
            15,
            77,
            Timestamp.from(now),
            "req-activated-15",
        )
        val status = AtomicReference<ClaimNpcRequestStatus>(ClaimNpcRequestStatus.Pending)
        val classifier = GeneralOwnershipClassifier(
            owners,
            ownershipBodies,
            ClaimNpcRequestStatusReader { _, _ -> status.get() },
        )

        TransactionTemplate(transactionManager).executeWithoutResult {
            val cachedCandidate = generals.findById(15).orElseThrow()
            assertEquals(PROCESS_WORLD_ID, cachedCandidate.worldId)
            assertEquals(null, cachedCandidate.userId)
            assertEquals(2, cachedCandidate.npcState)
            assertIs<GeneralOwnershipClassifier.Ownership.CorrelatedPending>(classifier.classify(77L))

            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }.executeWithoutResult {
                jdbc.update(
                    "UPDATE general SET user_id = ?, npc_state = ? WHERE world_id = ? AND id = ?",
                    "77",
                    1,
                    PROCESS_WORLD_ID,
                    15,
                )
            }
            status.set(ClaimNpcRequestStatus.Applied)

            val live = assertIs<GeneralOwnershipClassifier.Ownership.LiveOwned>(classifier.classify(77L))
            assertEquals(15, live.body.id)
            assertEquals(PROCESS_WORLD_ID, live.body.worldId)
            assertEquals("77", live.body.userId)
            assertEquals(1, live.body.npcState)
            assertEquals(15L, owners.findByUserId(77L)?.generalId)
        }
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, ?, 1, 1, 60) ON CONFLICT (id) DO NOTHING",
            id,
            "world-$id",
        )
    }

    private fun insertGeneral(id: Int, worldId: Int, userId: String?, npcState: Int) {
        jdbc.update(
            """
            INSERT INTO general (
                id, world_id, user_id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice,
                crew, crew_type_id, train, atmos, troop_id,
                weapon_code, book_code, horse_code, item_code, npc_state,
                turn_time, last_turn, meta
            ) VALUES (
                ?, ?, ?, ?, 0, 0, 50, 50, 50, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 0, 0,
                'None', 'None', 'None', 'None', ?,
                now(), '{}'::jsonb, '{}'::jsonb
            )
            """.trimIndent(),
            id,
            worldId,
            userId,
            "g$id",
            npcState,
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
