package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.controller.OperationController
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.OperationReadEntity
import opensamguk.gameapi.read.OperationReadRawRepository
import opensamguk.gameapi.read.OperationReadRepository
import opensamguk.gameapi.read.OperationUnitReadEntity
import opensamguk.gameapi.read.OperationUnitReadRawRepository
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRawRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.world.WorldFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.Date
import java.util.Optional

/** Production controller, JWT chain, permissions and process-world adapters; database access is mocked. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [OperationRetirementSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class OperationRetirementSecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = opensamguk.gameapi.security.ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier("", JWT_FIXTURE, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver(): GeneralResolver = mock(GeneralResolver::class.java)
        @Bean open fun generals(): GeneralReadRepository = mock(GeneralReadRepository::class.java)
        @Bean open fun cities(): CityReadRepository = mock(CityReadRepository::class.java)
        @Bean open fun retinue(): RetainerReadRepository = mock(RetainerReadRepository::class.java)
        @Bean open fun posts(): BoardPostReadRepository = mock(BoardPostReadRepository::class.java)
        @Bean open fun nations(): NationReadRepository = mock(NationReadRepository::class.java)
        @Bean open fun rawOperations(): OperationReadRawRepository = mock(OperationReadRawRepository::class.java)
        @Bean open fun rawUnits(): OperationUnitReadRawRepository = mock(OperationUnitReadRawRepository::class.java)
        @Bean open fun rawWorlds(): WorldStateReadRawRepository = mock(WorldStateReadRawRepository::class.java)
        @Bean open fun operations(raw: OperationReadRawRepository, units: OperationUnitReadRawRepository) =
            OperationReadRepository(raw, units, GameApiProcessWorld(WORLD_ID))
        @Bean open fun worlds(raw: WorldStateReadRawRepository) = WorldStateReadRepository(raw, GameApiProcessWorld(WORLD_ID))
        @Bean open fun permissions(nations: NationReadRepository) = SecretPermissionReader(nations)
        @Bean open fun controller(resolver: GeneralResolver, generals: GeneralReadRepository, cities: CityReadRepository,
            operations: OperationReadRepository, retinue: RetainerReadRepository, posts: BoardPostReadRepository,
            worlds: WorldStateReadRepository, permissions: SecretPermissionReader) =
            OperationController(resolver, generals, cities, operations, retinue, posts, worlds, permissions)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var retinue: RetainerReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var rawOperations: OperationReadRawRepository
    @Autowired lateinit var rawUnits: OperationUnitReadRawRepository
    @Autowired lateinit var rawWorlds: WorldStateReadRawRepository
    private lateinit var mvc: MockMvc

    private fun dependencies(): Array<Any> = arrayOf(resolver, generals, cities, retinue, posts, nations,
        rawOperations, rawUnits, rawWorlds)

    @BeforeEach
    fun setup() {
        reset(*dependencies())
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        resolve()
        val row = operation()
        `when`(rawOperations.findByWorldIdAndNationIdOrderByIdDesc(WORLD_ID, 1)).thenReturn(listOf(row))
        `when`(rawOperations.findByWorldIdAndId(WORLD_ID, 1)).thenReturn(row)
        `when`(rawOperations.findByWorldIdAndId(WORLD_ID, 9)).thenReturn(operation(nationId = 2))
        `when`(rawUnits.findByWorldIdAndOperationIdInOrderByIdAsc(WORLD_ID, listOf(1))).thenReturn(listOf(
            OperationUnitReadEntity(worldId = WORLD_ID, id = 11, operationId = 1, generalId = 10, role = "main")))
        `when`(rawWorlds.findById(WORLD_ID)).thenReturn(Optional.of(world(month = 3)))
        `when`(cities.findById(2)).thenReturn(Optional.of(CityReadEntity(id = 2, nationId = 1, name = "목표현")))
        `when`(cities.findById(1)).thenReturn(Optional.of(CityReadEntity(id = 1, nationId = 1, name = "출발현")))
        `when`(generals.findById(10)).thenReturn(Optional.of(general()))
        `when`(posts.findByOperationIds(listOf(1))).thenReturn(emptyList())
        clearInvocations(*dependencies())
    }

    @Test
    fun `production operation mapping keeps only the list`() {
        val mappings = context.getBean(RequestMappingHandlerMapping::class.java).handlerMethods
            .filterValues { it.beanType == OperationController::class.java }.keys.flatMap { it.patternValues }.toSet()
        assertEquals(setOf("/api/operations"), mappings)
        verifyNoInteractions(*dependencies())
    }

    @Test
    fun `missing expired invalid and refresh JWT reject list and retired detail before reads`() {
        for (path in listOf("/api/operations", "/api/operations/1", "/api/operations/9", "/api/operations/not-an-id")) {
            for (bearer in listOf(null, "Bearer invalid", "Bearer ${token(expired = true)}",
                "Bearer ${token(type = GatewayJwtClaims.REFRESH_TOKEN)}")) {
                val req = get(path).param("generalId", "999").param("nationId", "2")
                bearer?.let { req.header("Authorization", it) }
                mvc.perform(req).andExpect(status().isUnauthorized)
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json(AUTH_ERROR, true))
            }
        }
        verifyNoInteractions(*dependencies())
    }

    @Test
    fun `signed USER and ADMIN cannot dispatch own foreign absent or malformed retired details`() {
        for (role in listOf("USER", "ADMIN")) for (userId in listOf(7L, 8L)) {
            for (id in listOf("1", "9", "77", "not-an-id")) {
                mvc.perform(get("/api/operations/$id").param("generalId", "999").param("nationId", "2")
                    .header("Authorization", "Bearer ${token(userId = userId, role = role)}"))
                    .andExpect(status().isNotFound)
            }
        }
        verifyNoInteractions(*dependencies())
    }

    @Test
    fun `retained list uses verified identity permission and current process world`() {
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(get("/api/operations").param("generalId", "999").param("nationId", "2").param("worldId", "99")
                .header("Authorization", "Bearer ${token(role = role)}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.nationId").value(1))
                .andExpect(jsonPath("$.myGeneralId").value(10)).andExpect(jsonPath("$.myPermission").value(2))
                .andExpect(jsonPath("$.operations.length()").value(1))
                .andExpect(jsonPath("$.operations[0].title").value("본국 작전"))
                .andExpect(jsonPath("$.operations[0].target.name").value("목표현"))
                .andExpect(jsonPath("$.operations[0].units[0].name").value("내 장수"))
                .andExpect(jsonPath("$.operations[0].remainingMonths").value(2))
                .andExpect(jsonPath("$.rules.provisional").value(true))
        }
        verify(resolver, times(2)).resolve(7L)
        verify(rawOperations, times(2)).findByWorldIdAndNationIdOrderByIdDesc(WORLD_ID, 1)
        verify(rawUnits, times(2)).findByWorldIdAndOperationIdInOrderByIdAsc(WORLD_ID, listOf(1))
        verify(rawWorlds, times(2)).findById(WORLD_ID)
        verify(rawOperations, never()).findByWorldIdAndNationIdOrderByIdDesc(99, 2)
        verify(rawOperations, never()).findByWorldIdAndId(WORLD_ID, 1)
    }

    @Test
    fun `retained list reloads current operation and calendar on every request`() {
        val req = { get("/api/operations").header("Authorization", "Bearer ${token()}") }
        mvc.perform(req()).andExpect(status().isOk).andExpect(jsonPath("$.operations[0].remainingMonths").value(2))
        `when`(rawOperations.findByWorldIdAndNationIdOrderByIdDesc(WORLD_ID, 1))
            .thenReturn(listOf(operation(title = "갱신된 작전")))
        `when`(rawWorlds.findById(WORLD_ID)).thenReturn(Optional.of(world(month = 4)))
        mvc.perform(req()).andExpect(status().isOk)
            .andExpect(jsonPath("$.operations[0].title").value("갱신된 작전"))
            .andExpect(jsonPath("$.operations[0].remainingMonths").value(1))
        verify(rawOperations, times(2)).findByWorldIdAndNationIdOrderByIdDesc(WORLD_ID, 1)
        verify(rawWorlds, times(2)).findById(WORLD_ID)
    }

    @Test
    fun `retained nationless list is empty with rules before operation or world reads`() {
        resolve(nationId = 0)
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(get("/api/operations").header("Authorization", "Bearer ${token(role = role)}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.nationId").value(0))
                .andExpect(jsonPath("$.myGeneralId").value(10)).andExpect(jsonPath("$.myPermission").value(-1))
                .andExpect(jsonPath("$.operations").isEmpty).andExpect(jsonPath("$.rules.maxUnits").isNumber)
        }
        verifyNoInteractions(rawOperations, rawUnits, rawWorlds, generals, cities, retinue, posts, nations)
    }

    @Test
    fun `signed unresolved account keeps list 404 before permission and repository reads`() {
        for (role in listOf("USER", "ADMIN")) {
            mvc.perform(get("/api/operations").header("Authorization", "Bearer ${token(userId = 8L, role = role)}"))
                .andExpect(status().isNotFound)
        }
        verifyNoInteractions(rawOperations, rawUnits, rawWorlds, generals, cities, retinue, posts, nations)
    }

    private fun general(nationId: Int = 1) = GeneralReadEntity(id = 10, userId = "7", name = "내 장수",
        nationId = nationId, cityId = 1, officerLevel = 5, crew = 300)

    private fun resolve(nationId: Int = 1) {
        val general = general(nationId)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(general, 5, 2, nationId, 3))
    }

    private fun operation(nationId: Int = 1, title: String = "본국 작전") = OperationReadEntity(worldId = WORLD_ID,
        id = 1, nationId = nationId, kind = "capture_city", targetCityId = 2, title = title,
        declaredYear = 200.toShort(), declaredMonth = 2.toShort(), declaredPhase = 1.toShort(),
        deadlineYear = 200.toShort(), deadlineMonth = 5.toShort(), deadlinePhase = 1.toShort(), status = "active")

    private fun world(month: Int) = WorldStateReadEntity(id = WORLD_ID, currentYear = 200, currentMonth = month,
        currentPhase = 1, config = mapOf(WorldFormat.CONFIG_KEY to WorldFormat.GENERAL_RETAINER_CAMPAIGN.name))

    private fun token(userId: Long = 7L, role: String = "USER", type: String = GatewayJwtClaims.ACCESS_TOKEN,
        expired: Boolean = false): String {
        val now = Date()
        return Jwts.builder().subject(userId.toString()).issuedAt(Date(now.time - 120_000))
            .expiration(Date(now.time + if (expired) -60_000 else 600_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, type).claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_FIXTURE))).compact()
    }

    companion object {
        private const val WORLD_ID = 7
        private const val AUTH_ERROR = """{"error":{"code":"AUTH_REQUIRED","message":"로그인이 필요합니다."}}"""
        // Public synthetic fixture, shared with existing JWT-chain tests; no runtime credentials are used.
        private const val JWT_FIXTURE = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}
