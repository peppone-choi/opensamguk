package opensamguk.gateway.security

import opensamguk.gateway.dto.*
import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.service.*
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production JWT verification and current DB roles; all operating/mutating services are doubles. */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class AdminRouteAuthorizationHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var users: UserRepository
    @MockitoBean lateinit var deploy: DeployService
    @MockitoBean lateinit var version: VersionService
    @MockitoBean lateinit var members: AdminMemberService
    @MockitoBean lateinit var scenarios: ScenarioCatalogService
    @MockitoBean lateinit var notices: NoticeService
    private lateinit var admin: UserEntity
    private lateinit var member: UserEntity
    private val doubles get() = listOf(deploy, version, members, scenarios, notices)

    @BeforeEach
    fun fixtures() {
        admin = users.saveAndFlush(UserEntity(username = "admin-${UUID.randomUUID()}", password = "unused", role = "ADMIN"))
        member = users.saveAndFlush(UserEntity(username = "member-${UUID.randomUUID()}", password = "unused", role = "USER"))
        reset(*doubles.toTypedArray())
    }

    @AfterEach
    fun cleanup() {
        users.deleteById(admin.id)
        users.deleteById(member.id)
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    fun `all admin routes enforce actual current role before service binding`(route: Route, identity: String) {
        val expectedArgs = stub(route)
        val request = request(HttpMethod.valueOf(route.method), route.path)
            .contentType(MediaType.APPLICATION_JSON)
        route.body?.let(request::content)
        if (identity != "anonymous") {
            val user = if (identity == "ADMIN") admin else member
            request.header("Authorization", "Bearer ${tokens.generateAccessToken(user.id, user.role)}")
        }
        val response = mvc.perform(request).andReturn().response
        assertEquals(when (identity) { "anonymous" -> 401; "USER" -> 403; else -> 200 }, response.status, route.toString())
        if (identity != "ADMIN") {
            // Stubbing is not an invocation: denied requests must not enter any downstream service.
            verifyNoInteractions(*doubles.toTypedArray())
        } else {
            val target = target(route.service)
            val invocation = mockingDetails(target).invocations.single()
            assertEquals(route.operation, invocation.method.name)
            if (route.operation == "collect") {
                assertTrue(invocation.arguments.single() is ServiceVersion)
            } else {
                assertEquals(expectedArgs, invocation.arguments.toList())
            }
            doubles.filter { it !== target }.forEach { verifyNoInteractions(it) }
        }
    }

    @Test
    fun `a token ADMIN claim cannot retain privileges after database role downgrade`() {
        val token = tokens.generateAccessToken(admin.id, "ADMIN")
        admin.role = "USER"
        users.saveAndFlush(admin)
        assertEquals(403, mvc.perform(request(HttpMethod.GET, "/admin/users")
            .header("Authorization", "Bearer $token")).andReturn().response.status)
        verifyNoInteractions(*doubles.toTypedArray())
    }

    @Test
    fun `current DB role rather than issued claim grants admin authority`() {
        val token = tokens.generateAccessToken(member.id, "USER")
        member.role = "ADMIN"
        users.saveAndFlush(member)
        `when`(members.listUsers()).thenReturn(AdminUserListResponse(emptyList(), emptyList(), true, true))
        assertEquals(200, mvc.perform(request(HttpMethod.GET, "/admin/users")
            .header("Authorization", "Bearer $token")).andReturn().response.status)
        verify(members).listUsers()
    }

    @Test
    fun `deleted user refresh token malformed JWT and internal token never grant ADMIN`() {
        val old = tokens.generateAccessToken(admin.id, "ADMIN")
        val refresh = tokens.generateRefreshToken(member.id)
        users.deleteById(admin.id)
        for (token in listOf(old, refresh, "malformed.jwt", "internal-token")) {
            assertEquals(401, mvc.perform(request(HttpMethod.GET, "/admin/users")
                .header("Authorization", "Bearer $token")).andReturn().response.status)
        }
        verifyNoInteractions(*doubles.toTypedArray())
    }

    @Test
    fun `denied malformed write body never reaches parser or operating service`() {
        for (token in listOf(null, tokens.generateAccessToken(member.id, "USER"))) {
            val request = request(HttpMethod.POST, "/admin/deploy").contentType(MediaType.APPLICATION_JSON).content("not-json")
            if (token != null) request.header("Authorization", "Bearer $token")
            assertEquals(if (token == null) 401 else 403, mvc.perform(request).andReturn().response.status)
        }
        verifyNoInteractions(*doubles.toTypedArray())
    }

    @Test
    fun `standard error rendering does not execute the original admin handler`() {
        for (original in listOf("/admin/users", "/admin/turn-daemon/pause")) {
            val response = mvc.perform(request(HttpMethod.POST, "/error").with {
                it.dispatcherType = jakarta.servlet.DispatcherType.ERROR
                it.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, original)
                it.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
                it
            }).andReturn().response
            assertEquals(500, response.status)
        }
        verifyNoInteractions(*doubles.toTypedArray())
    }

    private fun target(service: String): Any = when (service) {
        "deploy" -> deploy; "version" -> version; "members" -> members; "scenarios" -> scenarios; else -> notices
    }

    private fun stub(route: Route): List<Any?> {
        val proxy = EnvProxyResponse(200, "{\"testOnly\":true}")
        val notice = NoticeResponse(42, "fixture", "fixture", false, "2026-01-01T00:00:00Z", false)
        return when (route.operation) {
            "collect" -> {
                doReturn(VersionResponse(ServiceVersion(true, null, null, null), emptyList(), false))
                    .`when`(version).collect(any(ServiceVersion::class.java) ?: ServiceVersion(true, null, null, null))
                emptyList()
            }
            "status" -> { `when`(deploy.status("fixture")).thenReturn(DeployStatus(false, "fixture", null, emptyList())); listOf("fixture") }
            "turnDaemonStatus" -> { `when`(deploy.turnDaemonStatus("fixture")).thenReturn(proxy); listOf("fixture") }
            "turnDaemonPause" -> { `when`(deploy.turnDaemonPause("fixture")).thenReturn(proxy); listOf("fixture") }
            "turnDaemonResume" -> { `when`(deploy.turnDaemonResume("fixture")).thenReturn(proxy); listOf("fixture") }
            "turnDaemonCatchUp" -> { `when`(deploy.turnDaemonCatchUp("fixture", 2)).thenReturn(proxy); listOf("fixture", 2) }
            "deploy" -> { `when`(deploy.deploy("fixture", "test-tag", admin.username)).thenReturn(DeployResult(true, "fixture")); listOf("fixture", "test-tag", admin.username) }
            "createServer" -> { `when`(deploy.createServer("{}")).thenReturn(proxy); listOf("{}") }
            "deleteServer" -> { `when`(deploy.deleteServer("fixture", "{}")).thenReturn(proxy); listOf("fixture", "{}") }
            "resetServer" -> { `when`(deploy.resetServer("fixture", "{}")).thenReturn(proxy); listOf("fixture", "{}") }
            "reconcileSatisfiedCreate" -> { `when`(deploy.reconcileSatisfiedCreate("fixture", OPERATION, "{}")).thenReturn(proxy); listOf("fixture", OPERATION, "{}") }
            "operationStatus" -> { `when`(deploy.operationStatus(OPERATION)).thenReturn(proxy); listOf(OPERATION) }
            "list" -> { `when`(scenarios.list()).thenReturn(ScenarioListResponse(emptyList())); emptyList() }
            "sharedEnv" -> { `when`(deploy.sharedEnv()).thenReturn(proxy); emptyList() }
            "patchSharedEnv" -> { `when`(deploy.patchSharedEnv("{}")).thenReturn(proxy); listOf("{}") }
            "serverEnv" -> { `when`(deploy.serverEnv("fixture")).thenReturn(proxy); listOf("fixture") }
            "patchServerEnv" -> { `when`(deploy.patchServerEnv("fixture", "{}")).thenReturn(proxy); listOf("fixture", "{}") }
            "listUsers" -> { `when`(members.listUsers()).thenReturn(AdminUserListResponse(emptyList(), emptyList(), true, true)); emptyList() }
            "setAllowLogin" -> { `when`(members.setAllowLogin(false)).thenReturn(SystemFlagResponse(true, false)); listOf(false) }
            "scrubDeleted" -> { `when`(members.scrubDeleted()).thenReturn(ScrubResult(0)); emptyList() }
            "runUserCommand" -> { `when`(members.runUserCommand(admin.id, "ADMIN", 42, "block", 1)).thenReturn(UserCommandResult(true)); listOf(admin.id, "ADMIN", 42L, "block", 1) }
            "banEmail" -> { `when`(members.banEmail("fixture@example.invalid")).thenReturn(BanEmailResult(true, "fixture")); listOf("fixture@example.invalid") }
            "adminList" -> { `when`(notices.adminList()).thenReturn(emptyList()); listOf(NoticeService.ADMIN_LIMIT) }
            "create" -> { val dto = NoticeUpsertRequest("fixture", "fixture", false); `when`(notices.create(dto, admin.id)).thenReturn(notice); listOf(dto, admin.id) }
            "update" -> { val dto = NoticeUpsertRequest("fixture", "fixture", false); `when`(notices.update(42, dto)).thenReturn(notice); listOf(42L, dto) }
            "setPinned" -> { `when`(notices.setPinned(42, true)).thenReturn(notice); listOf(42L, true) }
            "delete" -> { `when`(notices.delete(42)).thenReturn(notice); listOf(42L) }
            else -> error("Unknown test binding")
        }
    }

    data class Route(val method: String, val path: String, val service: String, val operation: String, val body: String? = null)

    companion object {
        private const val OPERATION = "0123456789abcdef0123456789abcdef"
        private const val NOTICE = "{\"title\":\"fixture\",\"body\":\"fixture\",\"pinned\":false}"
        private val ROUTES = listOf(
            Route("GET", "/admin/version", "version", "collect"),
            Route("GET", "/admin/deploy/status?serverId=fixture", "deploy", "status"),
            Route("GET", "/admin/turn-daemon/status?serverId=fixture", "deploy", "turnDaemonStatus"),
            Route("POST", "/admin/turn-daemon/pause?serverId=fixture", "deploy", "turnDaemonPause"),
            Route("POST", "/admin/turn-daemon/resume?serverId=fixture", "deploy", "turnDaemonResume"),
            Route("POST", "/admin/turn-daemon/catch-up?serverId=fixture", "deploy", "turnDaemonCatchUp", "{\"multiplier\":2}"),
            Route("POST", "/admin/deploy", "deploy", "deploy", "{\"serverId\":\"fixture\",\"tag\":\"test-tag\"}"),
            Route("POST", "/admin/servers", "deploy", "createServer", "{}"),
            Route("DELETE", "/admin/servers/fixture", "deploy", "deleteServer"),
            Route("POST", "/admin/servers/fixture/reset", "deploy", "resetServer"),
            Route("POST", "/admin/servers/fixture/operations/$OPERATION/reconcile-satisfied-create", "deploy", "reconcileSatisfiedCreate", "{}"),
            Route("GET", "/admin/servers/operations/$OPERATION", "deploy", "operationStatus"),
            Route("GET", "/admin/scenarios", "scenarios", "list"),
            Route("GET", "/admin/env/shared", "deploy", "sharedEnv"),
            Route("PATCH", "/admin/env/shared", "deploy", "patchSharedEnv", "{}"),
            Route("GET", "/admin/env/servers/fixture", "deploy", "serverEnv"),
            Route("PATCH", "/admin/env/servers/fixture", "deploy", "patchServerEnv", "{}"),
            Route("GET", "/admin/users", "members", "listUsers"),
            Route("POST", "/admin/system/allow_login", "members", "setAllowLogin", "{\"value\":false}"),
            Route("POST", "/admin/users/scrub/deleted", "members", "scrubDeleted"),
            Route("POST", "/admin/users/42/block", "members", "runUserCommand", "{\"param\":1}"),
            Route("POST", "/admin/ban-email", "members", "banEmail", "{\"email\":\"fixture@example.invalid\"}"),
            Route("GET", "/admin/notices", "notices", "adminList"),
            Route("POST", "/admin/notices", "notices", "create", NOTICE),
            Route("PUT", "/admin/notices/42", "notices", "update", NOTICE),
            Route("PATCH", "/admin/notices/42/pin", "notices", "setPinned", "{\"pinned\":true}"),
            Route("DELETE", "/admin/notices/42", "notices", "delete"),
        )
        @JvmStatic fun cases(): Stream<Arguments> = ROUTES.flatMap { route ->
            listOf("anonymous", "USER", "ADMIN").map { Arguments.of(route, it) }
        }.stream()
    }
}
