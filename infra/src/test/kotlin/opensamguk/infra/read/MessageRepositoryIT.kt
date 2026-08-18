package opensamguk.infra.read

import opensamguk.logic.message.MessageType
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-199 — locks the mailbox `type` predicates that 500'd with
 * `operator does not exist: message_type = character varying` (42883).
 *
 * Inserts via JDBC with `CAST('private' AS message_type)` (the write-path contract)
 * and reads through the same derived JPA methods MailboxController uses.
 * Removing `@JdbcType(PostgresValueEnumJdbcType::class)` from MessageEntity
 * must fail this test.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SideReadRepositoryConfiguration::class, WorldOneScopeConfiguration::class)
class MessageRepositoryIT {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var messages: MessageRepository

    @Test
    fun `message type predicates bind the PG enum without 42883`() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) " +
                "VALUES (1, 'fixture', 200, 1, 3600)",
        )
        jdbc.update(
            """
            INSERT INTO message (
                world_id, mailbox, type, src, dest, time, valid_until, message
            ) VALUES (
                1, 42, CAST('private' AS message_type), 7, 42,
                TIMESTAMPTZ '2026-08-18 00:00:00+00',
                TIMESTAMPTZ '9999-12-31 23:59:59+00',
                '{"text":"hi"}'::jsonb
            )
            """.trimIndent(),
        )

        val now = Instant.parse("2026-08-18T12:00:00Z")
        val byType = messages.findByMailboxAndType(42, MessageType.PRIVATE)
        val recent = messages.findByMailboxAndTypeAndValidUntilAfterOrderByIdDesc(
            42,
            MessageType.PRIVATE,
            now,
        )

        assertEquals(1, byType.size)
        assertEquals(MessageType.PRIVATE, byType.single().type)
        assertEquals(42, byType.single().mailbox)
        assertEquals(1, recent.size)
        assertEquals(byType.single().id, recent.single().id)
        assertTrue(recent.single().validUntil.isAfter(now))
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
