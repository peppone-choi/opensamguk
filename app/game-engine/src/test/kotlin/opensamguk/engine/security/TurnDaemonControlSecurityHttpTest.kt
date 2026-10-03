package opensamguk.engine.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterRegistration
import jakarta.servlet.ServletContext
import opensamguk.engine.boot.WorldStateAvailability
import opensamguk.engine.config.EngineProcessWorld
import opensamguk.engine.config.TurnDaemonControlSecurityConfiguration
import opensamguk.engine.run.TurnDaemonRunner
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.status.DaemonPauseGate
import opensamguk.engine.status.StatusController
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.ServletRequestPathUtils
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [TurnDaemonControlSecurityHttpTest.WebConfig::class])
class TurnDaemonControlSecurityHttpTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var registration: FilterRegistrationBean<TurnDaemonControlFilter>
    @Autowired lateinit var gate: DaemonPauseGate
    @Autowired lateinit var runner: TurnDaemonRunner
    @Autowired lateinit var config: WebConfig
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        gate.restore(false)
        reset(runner)
        clearInvocations(gate)
        config.interceptions.set(0)
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(registration.filter).build()
    }

    @Test
    fun `production registration covers every reentry dispatcher`() {
        val servlet = mock(ServletContext::class.java)
        val dynamic = mock(FilterRegistration.Dynamic::class.java)
        `when`(servlet.addFilter("turnDaemonControlFilter", registration.filter as Filter)).thenReturn(dynamic)
        registration.onStartup(servlet)
        verify(dynamic).addMappingForUrlPatterns(
            EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.ERROR, DispatcherType.ASYNC),
            false, "/*",
        )
        verify(dynamic).setAsyncSupported(true)
    }

    @Test
    fun `all three writes reject missing wrong or unrelated credentials before invocation`() {
        for (path in PATHS) for (token in listOf(null, "", "wrong", "user.access.jwt", "internal-token", OTHER_TOKEN)) {
            val request = post(path).contentType(MediaType.APPLICATION_JSON).content("{\"multiplier\":2}")
            if (token != null) request.header("Authorization", "Bearer $token")
            pins(request)
            mvc.perform(request).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(gate, runner)
        assertEquals(0, config.interceptions.get())
    }

    @Test
    fun `correct credential still requires single exact current pins`() {
        for (header in HEADERS) {
            val request = authenticated(PATHS.first())
            request.header(header, "other")
            mvc.perform(request).andExpect(status().isUnauthorized)
            mvc.perform(authenticated(PATHS.first()).with {
                it.removeHeader(header)
                it.addHeader(header, "other")
                it
            }).andExpect(status().isUnauthorized)
            mvc.perform(authenticated(PATHS.first()).with { it.removeHeader(header); it })
                .andExpect(status().isUnauthorized)
        }
        mvc.perform(authenticated(PATHS.first()).header("Authorization", "Bearer $TOKEN"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(gate, runner)
        assertEquals(0, config.interceptions.get())
    }

    @Test
    fun `every dispatch rejects without using a previously cached public path`() {
        for (type in listOf(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.ASYNC)) {
            mvc.perform(post(PATHS.first()).with {
                it.requestURI = "/previous-public-path"
                ServletRequestPathUtils.parseAndCache(it)
                it.requestURI = PATHS.first()
                it.dispatcherType = type
                it
            }).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(gate, runner)
        assertEquals(0, config.interceptions.get())
    }

    @Test
    fun `ambiguous paths never reach the controller with or without a credential`() {
        val aliases = listOf(
            "/admin/turn-daemon/%70ause", "/admin%2fturn-daemon/pause", "/admin/turn-daemon/pause;alias=x",
            "/admin//turn-daemon/pause", "/admin/turn-daemon/../turn-daemon/pause",
            "/admin/turn-daemon/pause/", "/admin/turn-daemon/%2570ause", "/admin/turn-daemon/%5cpause",
            "/admin/turn-daemon/PAUSE", "/admin/turn-daemon/pause.json",
        )
        for (path in aliases) for (credential in listOf(false, true)) {
            val request = if (credential) authenticated(path) else post(path)
            assertTrue(mvc.perform(request).andReturn().response.status in 400..599)
        }
        verifyNoInteractions(gate, runner)
        assertEquals(0, config.interceptions.get())
    }

    @Test
    fun `authorized controls retain real gate effects and catch up validation`() {
        mvc.perform(authenticated(PATHS[0])).andExpect(status().isOk)
        verify(gate).lock(10)
        assertTrue(gate.isPaused())
        mvc.perform(authenticated(PATHS[1])).andExpect(status().isOk)
        verify(gate).unlock()
        assertFalse(gate.isPaused())
        mvc.perform(authenticated(PATHS[2]).contentType(MediaType.APPLICATION_JSON).content("{\"multiplier\":3}"))
            .andExpect(status().isBadRequest)
        mvc.perform(authenticated(PATHS[2]).contentType(MediaType.APPLICATION_JSON).content("{\"multiplier\":2}"))
            .andExpect(status().isConflict)
        verify(runner, never()).setCatchUpMultiplier(anyInt())
    }

    @Test
    fun `MVC context path works and unsupported methods retain ordinary 405`() {
        mvc.perform(authenticated("/ctx${PATHS[0]}").contextPath("/ctx")).andExpect(status().isOk)
        verify(gate).lock(10)
        gate.restore(false)
        clearInvocations(gate, runner)
        mvc.perform(post("/admin/turn-daemon/status")).andExpect(status().isMethodNotAllowed)
        mvc.perform(get(PATHS[0])).andExpect(status().isMethodNotAllowed)
        mvc.perform(put(PATHS[0])).andExpect(status().isMethodNotAllowed)
        verifyNoInteractions(gate, runner)
    }

    @Test
    fun `authorized active catch up reaches only the requested allowed multiplier`() {
        for (multiplier in listOf(2, 4)) {
            val snapshot = opensamguk.common.turn.CatchUpSnapshot(true, multiplier, 10, 5, null)
            val diagnostics = runner.diagnostics().copy(catchUp = snapshot)
            doReturn(diagnostics).`when`(runner).diagnostics()
            doReturn(snapshot).`when`(runner).setCatchUpMultiplier(multiplier)
            clearInvocations(runner)
            mvc.perform(authenticated(PATHS[2]).contentType(MediaType.APPLICATION_JSON)
                .content("{\"multiplier\":$multiplier}")).andExpect(status().isOk)
            verify(runner).setCatchUpMultiplier(multiplier)
        }
        // Restore spy methods for the other test cases sharing this nonstarted runner.
        reset(runner)
    }

    @Test
    fun `failed control lookup closes only the write and never invokes MVC interceptors`() {
        val mappings = mock(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping::class.java)
        `when`(mappings.getHandler(any(jakarta.servlet.http.HttpServletRequest::class.java)
            ?: org.springframework.mock.web.MockHttpServletRequest()))
            .thenThrow(IllegalStateException("private-error-marker"))
        val alternate = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(TurnDaemonControlFilter(TurnDaemonControlBinding(BINDING, ObjectMapper(), EngineProcessWorld(991)), mappings))
            .build()
        val response = alternate.perform(authenticated(PATHS[0])).andReturn().response
        assertEquals(503, response.status)
        assertFalse(response.contentAsString.contains("private-error-marker"))
        verifyNoInteractions(gate, runner)
        assertEquals(0, config.interceptions.get())
    }

    @Test
    fun `GET status and unrelated GET survive lookup failure and missing binding`() {
        mvc.perform(get("/admin/turn-daemon/status")).andExpect(status().isOk)
        mvc.perform(get("/unrelated-read")).andExpect(status().isNotFound)
        val mappings = mock(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping::class.java)
        val missing = TurnDaemonControlBinding("", ObjectMapper(), EngineProcessWorld(991))
        val readMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(TurnDaemonControlFilter(missing, mappings)).build()
        readMvc.perform(get("/admin/turn-daemon/status")).andExpect(status().isOk)
        readMvc.perform(get("/actuator/health")).andExpect(status().isNotFound)
        verifyNoInteractions(mappings)
    }

    @Test
    fun `missing malformed config or process world mismatch closes writes`() {
        val request = org.springframework.mock.web.MockHttpServletRequest("POST", PATHS.first())
        request.addHeader("Authorization", "Bearer $TOKEN")
        HEADERS.zip(listOf("fixture", "991", "7", "test-revision")).forEach { (name, value) -> request.addHeader(name, value) }
        for (raw in listOf("", "{}", BINDING + "{}", BINDING.replace("\"generation\":7", "\"generation\":null"))) {
            assertFalse(TurnDaemonControlBinding(raw, ObjectMapper(), EngineProcessWorld(991)).permits(request))
        }
        assertFalse(TurnDaemonControlBinding(BINDING, ObjectMapper(), EngineProcessWorld(992)).permits(request))
        assertFalse(TurnDaemonControlBinding(BINDING, ObjectMapper(), EngineProcessWorld(991)).toString().contains(TOKEN))
    }

    private fun authenticated(path: String) = post(path).header("Authorization", "Bearer $TOKEN").also(::pins)
    private fun pins(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) {
        HEADERS.zip(listOf("fixture", "991", "7", "test-revision")).forEach { (name, value) -> request.header(name, value) }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(StatusController::class, TurnDaemonControlSecurityConfiguration::class)
    class WebConfig : WebMvcConfigurer {
        val interceptions = AtomicInteger()
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(object : HandlerInterceptor {
                override fun preHandle(request: jakarta.servlet.http.HttpServletRequest,
                    response: jakarta.servlet.http.HttpServletResponse, handler: Any): Boolean {
                    interceptions.incrementAndGet()
                    return true
                }
            })
        }
        @Bean fun objectMapper(): ObjectMapper = ObjectMapper().findAndRegisterModules()
        @Bean fun processWorld() = EngineProcessWorld(991)
        @Bean fun gate(): DaemonPauseGate = spy(DaemonPauseGate())
        @Bean fun runner(gate: DaemonPauseGate): TurnDaemonRunner {
            val provider = object : ObjectProvider<TurnRunService> {
                override fun getObject(vararg args: Any?): TurnRunService = error("world must not materialize")
                override fun getObject(): TurnRunService = error("world must not materialize")
                override fun getIfAvailable(): TurnRunService? = null
                override fun getIfUnique(): TurnRunService? = null
            }
            return spy(TurnDaemonRunner(provider, WorldStateAvailability { true }, gate, daemonEnabled = false, idlePollMs = 10))
        }
    }

    companion object {
        private val TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        private val OTHER_TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })
        private val BINDING = """{"serverId":"fixture","worldId":991,"generation":7,"revision":"test-revision","credential":"$TOKEN"}"""
        private val PATHS = listOf("/admin/turn-daemon/pause", "/admin/turn-daemon/resume", "/admin/turn-daemon/catch-up")
        private val HEADERS = listOf("X-Opensamguk-Control-Server-Id", "X-Opensamguk-Control-World-Id",
            "X-Opensamguk-Control-Generation", "X-Opensamguk-Control-Revision")
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("ENGINE_CONTROL_BINDING_JSON") { BINDING }
            registry.add("opensamguk.profile") { "test" }
        }
    }
}
