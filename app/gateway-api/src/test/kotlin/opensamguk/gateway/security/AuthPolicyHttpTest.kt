package opensamguk.gateway.security

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import jakarta.persistence.PersistenceException
import opensamguk.gateway.controller.AuthController
import opensamguk.gateway.dto.AuthPolicyResponse
import opensamguk.gateway.dto.LoginRequest
import opensamguk.gateway.dto.RegisterRequest
import opensamguk.gateway.service.AuthPolicyDeniedException
import opensamguk.gateway.service.AuthPolicyFailureCode
import opensamguk.gateway.service.AuthPolicyUnavailableException
import opensamguk.gateway.service.AuthService
import opensamguk.gateway.web.GlobalExceptionHandler
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import java.sql.SQLException

@WebMvcTest(AuthController::class)
@ContextConfiguration(classes = [AuthController::class])
@Import(
    SecurityConfig::class,
    JwtAuthenticationFilter::class,
    InternalServiceTokenFilter::class,
    PasswordEncoderConfig::class,
    GlobalExceptionHandler::class,
)
class AuthPolicyHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var authService: AuthService
    @MockitoBean lateinit var jwtTokenProvider: JwtTokenProvider
    @MockitoBean lateinit var userDetailsService: CustomUserDetailsService

    @ParameterizedTest
    @CsvSource("true,true", "true,false", "false,true", "false,false")
    fun `anonymous policy GET returns only two booleans with no store`(allowJoin: Boolean, allowLogin: Boolean) {
        `when`(authService.policy()).thenReturn(AuthPolicyResponse(allowJoin, allowLogin))

        mvc.perform(get("/auth/policy"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("""{"allow_join":$allowJoin,"allow_login":$allowLogin}""", true))
    }

    @Test
    fun `policy database failure returns unavailable without closed flags or database details`() {
        `when`(authService.policy()).thenThrow(
            AuthPolicyUnavailableException(DataAccessResourceFailureException("private database detail")),
        )

        mvc.perform(get("/auth/policy"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json(
                """{"message":"가입·로그인 허용 상태를 확인할 수 없습니다.","status":503}""", true,
            ))
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "begin", "commit"])
    fun `policy transaction lifecycle failures return unavailable without database details`(failurePoint: String) {
        val entityManagerFactory = mock(EntityManagerFactory::class.java)
        val databaseFailure = PersistenceException("private database detail", SQLException("private connection detail"))
        if (failurePoint == "create") {
            `when`(entityManagerFactory.createEntityManager()).thenThrow(databaseFailure)
        } else {
            val entityManager = mock(EntityManager::class.java)
            val transaction = mock(EntityTransaction::class.java)
            `when`(entityManagerFactory.createEntityManager()).thenReturn(entityManager)
            `when`(entityManager.transaction).thenReturn(transaction)
            if (failurePoint == "begin") {
                doThrow(databaseFailure).`when`(transaction).begin()
            } else {
                doThrow(databaseFailure).`when`(transaction).commit()
            }
        }
        val repository = mock(SystemFlagRepository::class.java)
        val target = AuthService(
            mock(UserRepository::class.java), mock(PasswordEncoder::class.java),
            mock(JwtTokenProvider::class.java), mock(AuthenticationManager::class.java),
            repository, mock(BannedMemberRepository::class.java), mock(EmailHasher::class.java),
        )
        val interceptor = TransactionInterceptor().apply {
            setTransactionManager(JpaTransactionManager(entityManagerFactory))
            setTransactionAttributeSource(AnnotationTransactionAttributeSource())
            afterPropertiesSet()
        }
        val transactionalService = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(interceptor)
        }.proxy as AuthService
        `when`(authService.policy()).thenAnswer { transactionalService.policy() }

        mvc.perform(get("/auth/policy"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json(
                """{"message":"가입·로그인 허용 상태를 확인할 수 없습니다.","status":503}""", true,
            ))
            .andExpect { result ->
                assertTrue(result.resolvedException is AuthPolicyUnavailableException)
                if (failurePoint == "commit") {
                    assertTrue(result.resolvedException?.cause is DataAccessException)
                } else {
                    assertTrue(result.resolvedException?.cause is CannotCreateTransactionException)
                }
                assertTrue(result.resolvedException?.cause?.cause is PersistenceException)
            }
        verify(entityManagerFactory).createEntityManager()
        if (failurePoint == "commit") {
            verify(repository).findSingleton()
        } else {
            verifyNoInteractions(repository)
        }
    }

    @Test
    fun `policy permission does not expose other methods neighboring routes or member data`() {
        for (request in listOf(
            post("/auth/policy"), delete("/auth/policy"), get("/auth/policy/extra"),
            get("/auth/policy/"), get("/auth/me"), post("/auth/account/password"),
            delete("/auth/account"), get("/admin/users"),
        )) {
            mvc.perform(request).andExpect(status().isUnauthorized)
        }
        mvc.perform(get("/admin/users").with(user("member").roles("USER")))
            .andExpect(status().isForbidden)
        verifyNoInteractions(authService)
    }

    @Test
    fun `join denial keeps legacy 400 message and adds JOIN_DISABLED`() {
        val request = RegisterRequest("tester", "password123", null, "테스터")
        `when`(authService.register(request)).thenThrow(AuthPolicyDeniedException(AuthPolicyFailureCode.JOIN_DISABLED))

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"tester","password":"password123","nickname":"테스터"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(content().json(
                """{"message":"현재는 가입이 금지되어있습니다!","status":400,"code":"JOIN_DISABLED"}""", true,
            ))
    }

    @Test
    fun `login denial keeps legacy 400 message and adds LOGIN_DISABLED`() {
        `when`(authService.login(LoginRequest("tester", "password123")))
            .thenThrow(AuthPolicyDeniedException(AuthPolicyFailureCode.LOGIN_DISABLED))

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"tester","password":"password123"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(content().json(
                """{"message":"현재는 로그인이 금지되어있습니다!","status":400,"code":"LOGIN_DISABLED"}""", true,
            ))
    }

    @Test
    fun `bad credentials remain 401 without a policy code`() {
        `when`(authService.login(LoginRequest("tester", "incorrect")))
            .thenThrow(BadCredentialsException("bad credentials"))

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"tester","password":"incorrect"}"""))
            .andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"message":"아이디나 비밀번호가 올바르지 않습니다.","status":401}""", true))
    }

    @Test
    fun `ordinary argument errors omit code and preserve their existing response`() {
        `when`(authService.login(LoginRequest("tester", "password123")))
            .thenThrow(IllegalArgumentException("사용자를 찾을 수 없습니다."))

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"tester","password":"password123"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"message":"사용자를 찾을 수 없습니다.","status":400}""", true))
    }

    @Test
    fun `input validation remains 400 without a policy code`() {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"","password":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").doesNotExist())
        verifyNoInteractions(authService)
    }
}
