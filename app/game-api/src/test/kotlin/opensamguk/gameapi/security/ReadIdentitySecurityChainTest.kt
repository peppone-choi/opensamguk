package opensamguk.gameapi.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.controller.BoardController
import opensamguk.gameapi.controller.FrontInfoController
import opensamguk.gameapi.controller.WorldMapController
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardCommentReadRepository
import opensamguk.gameapi.read.BoardPostReadEntity
import opensamguk.gameapi.read.BoardPostReadLogRepository
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadEntity
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.LogFeedReadRepository
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.ScenarioTitleResolver
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.VotePollReadRepository
import opensamguk.gameapi.read.VoteReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.web.CityDetailController
import opensamguk.gameapi.web.ReservedCommandsController
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
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
import java.util.Date
import java.util.Optional

/** JWT -> actual security chain -> actual read controllers. Every repository is local and mocked. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ReadIdentitySecurityChainTest.Config::class, GameApiSecurityConfig::class])
class ReadIdentitySecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver(): GeneralResolver = mock(GeneralResolver::class.java)
        @Bean open fun world(): WorldStateReadRepository = mock(WorldStateReadRepository::class.java)
        @Bean open fun generals(): GeneralReadRepository = mock(GeneralReadRepository::class.java)
        @Bean open fun nations(): NationReadRepository = mock(NationReadRepository::class.java)
        @Bean open fun cities(): CityReadRepository = mock(CityReadRepository::class.java)
        @Bean open fun ranks(): RankDataReadRepository = mock(RankDataReadRepository::class.java)
        @Bean open fun polls(): VotePollReadRepository = mock(VotePollReadRepository::class.java)
        @Bean open fun votes(): VoteReadRepository = mock(VoteReadRepository::class.java)
        @Bean open fun troops(): TroopReadRepository = mock(TroopReadRepository::class.java)
        @Bean open fun turns(): GeneralTurnReadRepository = mock(GeneralTurnReadRepository::class.java)
        @Bean open fun feeds(): LogFeedReadRepository = mock(LogFeedReadRepository::class.java)
        @Bean open fun env(): NationEnvReadRepository = mock(NationEnvReadRepository::class.java)
        @Bean open fun posts(): BoardPostReadRepository = mock(BoardPostReadRepository::class.java)
        @Bean open fun comments(): BoardCommentReadRepository = mock(BoardCommentReadRepository::class.java)
        @Bean open fun reads(): BoardPostReadLogRepository = mock(BoardPostReadLogRepository::class.java)
        @Bean open fun city(resolver: GeneralResolver, cities: CityReadRepository, generals: GeneralReadRepository,
            nations: NationReadRepository, world: WorldStateReadRepository) =
            CityDetailController(resolver, cities, generals, nations, world)
        @Bean open fun map(resolver: GeneralResolver, world: WorldStateReadRepository, generals: GeneralReadRepository,
            nations: NationReadRepository, cities: CityReadRepository) = WorldMapController(resolver, world, generals, nations, cities)
        @Bean open fun front(resolver: GeneralResolver, world: WorldStateReadRepository, generals: GeneralReadRepository,
            nations: NationReadRepository, cities: CityReadRepository, ranks: RankDataReadRepository, polls: VotePollReadRepository,
            votes: VoteReadRepository, troops: TroopReadRepository, turns: GeneralTurnReadRepository, feeds: LogFeedReadRepository,
            env: NationEnvReadRepository) = FrontInfoController(resolver, world, generals, nations, cities, ranks,
                polls, votes, troops, turns, feeds, env, ObjectMapper(), ScenarioTitleResolver())
        @Bean open fun reserved(resolver: GeneralResolver, turns: GeneralTurnReadRepository, world: WorldStateReadRepository,
            generals: GeneralReadRepository) = ReservedCommandsController(resolver, turns, world, generals,
                CommandRegistry(GeneralActionPipeline()))
        @Bean open fun board(posts: BoardPostReadRepository, comments: BoardCommentReadRepository, resolver: GeneralResolver,
            generals: GeneralReadRepository, polls: VotePollReadRepository, votes: VoteReadRepository,
            reads: BoardPostReadLogRepository, world: WorldStateReadRepository) =
            BoardController(posts, comments, resolver, generals, polls, votes, reads, world)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var world: WorldStateReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var turns: GeneralTurnReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        reset(resolver, world, generals, nations, cities, turns, posts)
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        resolve()
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "본국", gold = 321,
            rice = 654, meta = mapOf("spy" to mapOf("5" to 2)))))
        `when`(generals.findById(202)).thenReturn(Optional.of(GeneralReadEntity(id = 202, name = "타인 비밀", nationId = 2, cityId = 5)))
        `when`(cities.findById(1)).thenReturn(Optional.of(CityReadEntity(id = 1, nationId = 1, population = 1234)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, nationId = 2, population = 9876)))
        `when`(cities.findById(6)).thenReturn(Optional.of(CityReadEntity(id = 6, nationId = 2, population = 8888)))
        `when`(generals.findByOfficerCityAndOfficerLevelInOrderByIdAsc(5, listOf(4, 3, 2)))
            .thenReturn(listOf(GeneralReadEntity(id = 202, name = "비공개 태수", officerLevel = 4)))
    }

    private fun resolve(nationId: Int = 1, officerLevel: Int = 1) {
        val general = GeneralReadEntity(id = 101, userId = "7", name = "내 장수", nationId = nationId,
            cityId = 1, officerLevel = officerLevel, gold = 77)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(general, officerLevel,
            GeneralResolver.derivePermission(officerLevel), nationId, 1))
        `when`(resolver.resolveGeneralId(7L)).thenReturn(101)
    }

    private fun token(userId: Long = 7L): String {
        val now = Date()
        return Jwts.builder().subject(userId.toString()).issuedAt(now).expiration(Date(now.time + 60_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    @Test
    fun `anonymous and invalid bearer cannot impersonate via generalId and verified caller cannot substitute another body`() {
        for (path in listOf("/api/city/5", "/api/front-info", "/api/map")) {
            mvc.perform(get(path).param("generalId", "202")).andExpect(status().isUnauthorized)
            mvc.perform(get(path).param("generalId", "202").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized)
            mvc.perform(get(path).param("generalId", "202").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isForbidden)
            mvc.perform(get(path).param("generalId", "101").header("Authorization", "Bearer ${token(8)}"))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(world, cities, generals, nations)
    }

    @Test
    fun `public city front and map preserve statics while private values and fog remain masked`() {
        for (bearer in listOf<String?>(null, "invalid")) {
            fun request(path: String) = get(path).also { if (bearer != null) it.header("Authorization", "Bearer $bearer") }
            mvc.perform(request("/api/city/5")).andExpect(status().isOk)
                .andExpect(jsonPath("$.nationId").value(2)).andExpect(jsonPath("$.visible").value(false))
                .andExpect(jsonPath("$.population").doesNotExist()).andExpect(jsonPath("$.generals").isEmpty)
                .andExpect(jsonPath("$.generalNames").isEmpty).andExpect(jsonPath("$.officerGovernor.name").value("-"))
            mvc.perform(request("/api/front-info")).andExpect(status().isOk)
                .andExpect(jsonPath("$.general.hasGeneral").value(false)).andExpect(jsonPath("$.general.gold").value(0))
                .andExpect(jsonPath("$.nation").doesNotExist()).andExpect(jsonPath("$.city").doesNotExist())
                .andExpect(jsonPath("$.recentRecord.general").isEmpty)
            mvc.perform(request("/api/map?showMe=1")).andExpect(status().isOk)
                .andExpect(jsonPath("$.myCity").doesNotExist()).andExpect(jsonPath("$.myNation").doesNotExist())
                .andExpect(jsonPath("$.spyList").isEmpty).andExpect(jsonPath("$.shownByGeneralList").isEmpty)
        }
        verifyNoInteractions(resolver)
        verify(generals, never()).findByOfficerCityAndOfficerLevelInOrderByIdAsc(5, listOf(4, 3, 2))
    }

    @Test
    fun `verified owned body reveals own values and authorized spy while unrelated city stays masked`() {
        for (confirm in listOf(false, true)) {
            fun request(path: String) = get(path).header("Authorization", "Bearer ${token()}")
                .also { if (confirm) it.param("generalId", "101") }
            mvc.perform(request("/api/front-info")).andExpect(status().isOk)
                .andExpect(jsonPath("$.general.generalId").value(101)).andExpect(jsonPath("$.general.gold").value(77))
            mvc.perform(request("/api/map?showMe=1")).andExpect(status().isOk)
                .andExpect(jsonPath("$.myCity").value(1)).andExpect(jsonPath("$.myNation").value(1))
                .andExpect(jsonPath("$.spyList.5").value(2))
            mvc.perform(request("/api/city/1")).andExpect(status().isOk)
                .andExpect(jsonPath("$.population").value(1234))
            mvc.perform(request("/api/city/5")).andExpect(status().isOk)
                .andExpect(jsonPath("$.population").value(9876)).andExpect(jsonPath("$.officerGovernor.name").value("비공개 태수"))
            mvc.perform(request("/api/city/6")).andExpect(status().isOk)
                .andExpect(jsonPath("$.visible").value(false)).andExpect(jsonPath("$.population").doesNotExist())
        }
    }

    @Test
    fun `reserved orders require JWT and owned general`() {
        val path = "/api/reserved-commands?generalId=101"
        mvc.perform(get(path)).andExpect(status().isForbidden)
        mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isForbidden)
        mvc.perform(get(path).header("Authorization", "Bearer ${token(8)}")).andExpect(status().isForbidden)
        mvc.perform(get("/api/reserved-commands?generalId=202").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(turns, world)
        `when`(turns.findByGeneralIdOrderByTurnIdxAsc(101)).thenReturn(listOf(GeneralTurnReadEntity(
            id = 1, generalId = 101, turnIdx = 0, actionCode = "Move", arg = mapOf("destCityID" to 5))))
        mvc.perform(get(path).header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.slots[0].arg.destCityID").value(5))
    }

    @Test
    fun `council rejects anonymous nationless foreign nation and unresolved account without global fallback`() {
        for (path in listOf("/api/board", "/api/board?secret=true")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized)
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized)
            mvc.perform(get(path).header("Authorization", "Bearer ${token(8)}")).andExpect(status().isForbidden)
            mvc.perform(get(path).param("nationId", "2").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isForbidden)
        }
        resolve(nationId = 0, officerLevel = 12)
        for (secret in listOf(false, true)) {
            mvc.perform(get("/api/board").param("secret", secret.toString()).header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(posts)
    }

    @Test
    fun `same nation council returns only own posts and secret board requires chief permission`() {
        `when`(posts.findByIsSecretOrderByCreatedAtDescIdDesc(false)).thenReturn(listOf(BoardPostReadEntity(
            id = 2, nationId = 2, title = "타국 비밀", contentHtml = "타국 작전")))
        `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, false)).thenReturn(listOf(BoardPostReadEntity(
            id = 1, nationId = 1, title = "본국 회의", contentHtml = "본국 작전")))
        mvc.perform(get("/api/board?nationId=1").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles.length()").value(1))
            .andExpect(jsonPath("$.articles[0].nationId").value(1)).andExpect(jsonPath("$.articles[0].title").value("본국 회의"))
        mvc.perform(get("/api/board?secret=true").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        verify(posts, never()).findByIsSecretOrderByCreatedAtDescIdDesc(false)
        resolve(officerLevel = 5)
        `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, true)).thenReturn(listOf(BoardPostReadEntity(
            id = 3, nationId = 1, isSecret = true, title = "본국 기밀")))
        mvc.perform(get("/api/board?secret=true").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles[0].title").value("본국 기밀"))
    }

    companion object {
        const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}
