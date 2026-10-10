package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.controller.BoardController
import opensamguk.gameapi.council.*
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.logic.council.*
import opensamguk.logic.input.PoliticalInput
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date
import java.util.Optional
import kotlin.test.assertEquals

/** Same stored fixture and real RSA/ownership chain for Council's authority contract and legacy Board. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [BoardDesignationSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class BoardDesignationSecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(keys.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun owners(): GeneralOwnerRepository = mock(GeneralOwnerRepository::class.java)
        @Bean open fun generals(): GeneralReadRepository = mock(GeneralReadRepository::class.java)
        @Bean open fun nations(): NationReadRepository = mock(NationReadRepository::class.java)
        @Bean open fun worlds(): WorldStateReadRepository = mock(WorldStateReadRepository::class.java)
        @Bean open fun posts(): BoardPostReadRepository = mock(BoardPostReadRepository::class.java)
        @Bean open fun comments(): BoardCommentReadRepository = mock(BoardCommentReadRepository::class.java)
        @Bean open fun reads(): BoardPostReadLogRepository = mock(BoardPostReadLogRepository::class.java)
        @Bean open fun polls(): VotePollReadRepository = mock(VotePollReadRepository::class.java)
        @Bean open fun votes(): VoteReadRepository = mock(VoteReadRepository::class.java)
        @Bean open fun kv(): GameKvReadRepository = mock(GameKvReadRepository::class.java)
        @Bean open fun authority(kv: GameKvReadRepository) = CouncilAuthorityReader(kv)
        @Bean open fun resolver(owners: GeneralOwnerRepository, generals: GeneralReadRepository,
            nations: NationReadRepository) = GeneralResolver(owners, generals, nations)
        @Bean open fun board(posts: BoardPostReadRepository, comments: BoardCommentReadRepository,
            resolver: GeneralResolver, generals: GeneralReadRepository, polls: VotePollReadRepository,
            votes: VoteReadRepository, reads: BoardPostReadLogRepository, worlds: WorldStateReadRepository,
            reader: CouncilReader) = BoardController(posts, comments, resolver, generals, polls, votes, reads, worlds,
                secretAccess = BoardSecretAccessQuery(reader, worlds))
        @Bean open fun councilReader(resolver: GeneralResolver, worlds: WorldStateReadRepository,
            nations: NationReadRepository, generals: GeneralReadRepository, posts: BoardPostReadRepository,
            comments: BoardCommentReadRepository, reads: BoardPostReadLogRepository, authority: CouncilAuthorityReader) =
            CouncilReader(resolver, worlds, nations, generals, posts, comments,
                reads, authority, GameApiProcessWorld(1))
        @Bean open fun council(reader: CouncilReader) = CouncilController(reader)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var owners: GeneralOwnerRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var comments: BoardCommentReadRepository
    @Autowired lateinit var reads: BoardPostReadLogRepository
    @Autowired lateinit var polls: VotePollReadRepository
    @Autowired lateinit var votes: VoteReadRepository
    @Autowired lateinit var kv: GameKvReadRepository
    @Autowired lateinit var authority: CouncilAuthorityReader
    private lateinit var mvc: MockMvc

    @BeforeEach fun prepare() {
        reset(owners, generals, nations, worlds, posts, comments, reads, polls, votes, kv)
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun fixture(office: Int, grant: Boolean, revoked: Boolean = false) {
        val ruler = GeneralReadEntity(id = 10, worldId = 1, userId = "9", nationId = 1,
            name = "QA 군주", officerLevel = 12, npcState = 0, meta = mapOf("lord" to true))
        val actor = GeneralReadEntity(id = 11, worldId = 1, userId = "7", nationId = 1,
            name = "QA 본인", officerLevel = office, npcState = 0, meta = mapOf("lord" to false))
        val nation = NationReadEntity(id = 1, worldId = 1, name = "QA 세력", level = 1,
            meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-1", PoliticalInput.RISE))
        val grants = if (grant) listOf(CouncilDesignation("grant-1", 1, 10, "ruler-1", 11, "grant-1",
            if (revoked) "revoke-1" else null)) else emptyList()
        val world = WorldStateReadEntity(id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"),
            meta = mapOf(CouncilDesignationCodec.META_KEY to CouncilDesignationCodec.encode(
                CouncilDesignationState(if (revoked) "revoke-1" else "grant-1", grants))))
        `when`(generals.findByUserId("7")).thenReturn(actor)
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(listOf(ruler, actor))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation))
        `when`(worlds.findProcessWorld()).thenReturn(world)
        val article = BoardPostReadEntity(id = 40, worldId = 1, nationId = 1, isSecret = true,
            authorGeneralId = 10, authorName = "QA 군주", title = "QA 기밀", contentHtml = "fixture-secret")
        `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, true)).thenReturn(listOf(article))
        `when`(posts.councilPage(1, true, listOf("general", "operation", "notice"), null, null, 51))
            .thenReturn(listOf(article))
        assertEquals(if (grant && !revoked) setOf(10, 11) else setOf(10),
            authority.read(world, nation, listOf(ruler, actor)).readers)
    }

    private fun canonicalRead(allowed: Boolean) {
        val result = mvc.perform(get("/api/council").param("room", "SECRET")
            .header("Authorization", "Bearer ${access()}"))
        if (allowed) result.andExpect(status().isOk).andExpect(jsonPath("$.articles[0].id").value(40))
        else result.andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("STATE_UNAVAILABLE"))
        // The unavailable vassal source stays unavailable; no fabricated complete ACL is supplied.
    }

    @Test fun `unassigned high office must not expose a secret body through the authenticated legacy route`() {
        fixture(office = 5, grant = false)
        canonicalRead(false)
        clearInvocations(posts)
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(jsonPath("$.articles").isEmpty)
        verifyNoInteractions(posts)
    }

    @Test fun `designated ordinary officer must be able to read the same stored secret article`() {
        fixture(office = 1, grant = true)
        canonicalRead(true)
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles[0].id").value(40))
    }

    @Test fun `revoked high office must not retain secret reading authority through its old rank`() {
        fixture(office = 5, grant = true, revoked = true)
        canonicalRead(false)
        clearInvocations(posts)
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(jsonPath("$.articles").isEmpty)
        verifyNoInteractions(posts)
    }

    @Test fun `persistent ruler can read without a designation receipt or an office promotion`() {
        fixture(office = 1, grant = false)
        val people = generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)
        people[0].userId = "7"
        people[0].officerLevel = 1
        people[1].userId = "8"
        `when`(generals.findByUserId("7")).thenReturn(people[0])
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles[0].id").value(40))
    }

    @Test fun `unavailable malformed ledger cannot be replaced by a high office`() {
        fixture(office = 12, grant = false)
        worlds.findProcessWorld()!!.meta = mapOf(CouncilDesignationCodec.META_KEY to "malformed")
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles").isEmpty)
            .andExpect(jsonPath("$.blockedReason").value(BoardSecretAccessQuery.UNAVAILABLE))
        verifyNoInteractions(posts, comments, reads, polls, votes)
    }

    @Test fun `mismatched process world fails closed with the existing board response shape`() {
        fixture(office = 5, grant = true)
        worlds.findProcessWorld()!!.id = 2
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.articles").isEmpty)
            .andExpect(jsonPath("$.blockedReason").value(BoardSecretAccessQuery.UNAVAILABLE))
        verifyNoInteractions(posts, comments, reads, polls, votes)
    }

    @Test fun `old ruler designation does not survive a different durable ruler revision`() {
        fixture(office = 5, grant = true)
        nations.findById(1).get().meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-2", PoliticalInput.RISE)
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles").isEmpty)
        verifyNoInteractions(posts, comments, reads, polls, votes)
    }

    @Test fun `ordinary meeting remains readable without an available designation ledger`() {
        fixture(office = 1, grant = false)
        worlds.findProcessWorld()!!.meta = mapOf(CouncilDesignationCodec.META_KEY to "malformed")
        clearInvocations(kv)
        `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, false)).thenReturn(listOf(
            BoardPostReadEntity(id = 41, worldId = 1, nationId = 1, title = "QA 회의", contentHtml = "본문")))
        mvc.perform(get("/api/board").param("secret", "false").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles[0].id").value(41))
        verifyNoInteractions(kv)
    }

    @Test fun `anonymous caller cannot query secret fixtures on either route`() {
        for (path in listOf("/api/board", "/api/council")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(owners, generals, nations, worlds, posts, comments, reads, polls, votes, kv)
    }

    private fun access(): String {
        val now = System.currentTimeMillis()
        return Jwts.builder().subject("7").issuer(GatewayJwtContract.ISSUER)
            .audience().add(GatewayJwtContract.GAME_API_AUDIENCE).and()
            .issuedAt(Date(now - 120_000)).expiration(Date(now + 600_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(keys.private).compact()
    }

    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
