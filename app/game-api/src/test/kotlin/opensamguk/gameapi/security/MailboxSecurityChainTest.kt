package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.controller.MailboxController
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.infra.entity.MessageEntity
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.message.Mailbox
import opensamguk.logic.message.MessageType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.Date
import java.util.Optional

/** Real JWT filter, security chain and mailbox controller; repositories have no external services. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [MailboxSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class MailboxSecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun messages(): MessageRepository = mock(MessageRepository::class.java)
        @Bean open fun resolver(): GeneralResolver = mock(GeneralResolver::class.java)
        @Bean open fun controller(messages: MessageRepository, resolver: GeneralResolver) =
            MailboxController(messages, resolver)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var messages: MessageRepository
    @Autowired lateinit var resolver: GeneralResolver
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        reset(messages, resolver)
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        resolve(GeneralReadEntity(id = 101, userId = "7", nationId = 2, npcState = 0))
    }

    private fun resolve(general: GeneralReadEntity) {
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(
            general, general.officerLevel, GeneralResolver.derivePermission(general.officerLevel), general.nationId, 1,
        ))
    }

    private fun token(userId: Long = 7L): String {
        val now = Date()
        return Jwts.builder().subject(userId.toString()).issuedAt(now).expiration(Date(now.time + 60_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    private fun row(id: Int, mailbox: Int): MessageEntity = MessageEntity(
        id = id, mailbox = mailbox,
        type = when (mailbox) {
            Mailbox.PUBLIC -> MessageType.PUBLIC
            Mailbox.NATIONAL_BASE + 2 -> MessageType.NATIONAL
            else -> MessageType.PRIVATE
        },
        src = 101, dest = 101, time = Instant.EPOCH, validUntil = Instant.parse("2999-01-01T00:00:00Z"),
        message = """{"text":"수신 우편함의 서신","src":{"id":101},"dest":{"id":101}}""",
    )

    private fun <T : Any> anyNonNull(fallback: T): T {
        any<T>()
        return fallback
    }

    @Test
    fun `security chain blocks anonymous and invalid bearer before reading any mailbox`() {
        for (path in listOf("/api/mailbox/101", "/api/mailbox/101/unread", "/api/messages/1",
            "/api/mailbox/recent", "/api/mailbox/old?to=2&type=private")) {
            mvc.perform(get(path)).andExpect(status().isForbidden)
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isForbidden)
        }
        verifyNoInteractions(messages, resolver)
    }

    @Test
    fun `owned private national and public mailboxes return content with a real access JWT`() {
        `when`(messages.findByMailboxOrderById(anyInt())).thenAnswer { listOf(row(1, it.getArgument(0))) }
        `when`(messages.findByMailboxAndValidUntilAfter(anyInt(), anyNonNull(Instant.EPOCH)))
            .thenAnswer { listOf(row(1, it.getArgument(0))) }
        for (mailbox in listOf(101, Mailbox.NATIONAL_BASE + 2, Mailbox.PUBLIC)) {
            for (suffix in listOf("", "/unread")) {
                mvc.perform(get("/api/mailbox/$mailbox$suffix").header("Authorization", "Bearer ${token()}"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$[0].text").value("수신 우편함의 서신"))
            }
        }
    }

    @Test
    fun `foreign private and national IDs remain forbidden even with a forged general query`() {
        for (mailbox in listOf(202, Mailbox.NATIONAL_BASE + 3, Mailbox.NATIONAL_BASE, -1, 10000)) {
            for (suffix in listOf("", "/unread")) {
                mvc.perform(get("/api/mailbox/$mailbox$suffix").param("generalId", "202")
                    .header("Authorization", "Bearer ${token()}"))
                    .andExpect(status().isForbidden)
            }
        }
        verifyNoInteractions(messages)
    }

    @Test
    fun `single message access follows stored receiving mailbox for all allowed scopes`() {
        for ((index, mailbox) in listOf(101, Mailbox.NATIONAL_BASE + 2, Mailbox.PUBLIC).withIndex()) {
            `when`(messages.findById(index + 1)).thenReturn(Optional.of(row(index + 1, mailbox)))
            mvc.perform(get("/api/messages/${index + 1}").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.text").value("수신 우편함의 서신"))
        }
    }

    @Test
    fun `sender dest and body targets cannot grant access to a foreign receiving mailbox`() {
        for ((index, mailbox) in listOf(202, Mailbox.NATIONAL_BASE + 3).withIndex()) {
            // Both routing targets and body targets name the caller; the receiving mailbox still denies access.
            `when`(messages.findById(index + 1)).thenReturn(Optional.of(row(index + 1, mailbox)))
            mvc.perform(get("/api/messages/${index + 1}").param("generalId", "202")
                .header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.text").doesNotExist())
        }
    }

    @Test
    fun `authenticated account without a playable general cannot read correspondence`() {
        for (path in listOf("/api/mailbox/101", "/api/mailbox/101/unread", "/api/messages/1", "/api/mailbox/9999")) {
            mvc.perform(get(path).header("Authorization", "Bearer ${token(8L)}"))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(messages)
    }

    @Test
    fun `nationless general has no national mailbox and unknown message returns 404 after authentication`() {
        resolve(GeneralReadEntity(id = 101, userId = "7", nationId = 0, npcState = 0))
        mvc.perform(get("/api/mailbox/9000").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        mvc.perform(get("/api/mailbox/9000/unread").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        `when`(messages.findById(1)).thenReturn(Optional.of(row(1, 9000)))
        mvc.perform(get("/api/messages/1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        `when`(messages.findById(999)).thenReturn(Optional.empty())
        mvc.perform(get("/api/messages/999").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isNotFound)
    }

    companion object {
        const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}
