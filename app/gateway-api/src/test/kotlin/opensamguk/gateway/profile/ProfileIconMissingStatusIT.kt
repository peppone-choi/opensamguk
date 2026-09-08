package opensamguk.gateway.profile

import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/** A real servlet ERROR dispatch is essential: MockMvc alone does not reproduce it. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProfileIconMissingStatusIT {
    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var tokens: JwtTokenProvider

    private lateinit var token: String

    @BeforeEach
    fun createUserWithoutPersonalPortrait() {
        users.deleteAll()
        val user = users.saveAndFlush(UserEntity(
            username = "portrait-missing", password = "encoded", nickname = "portrait-missing",
            picture = ProfileIconService.DEFAULT_ICON,
        ))
        token = tokens.generateAccessToken(user.id, user.role)
    }

    @ParameterizedTest
    @ValueSource(strings = ["crops", "source"])
    fun `authenticated missing portrait stays 404 through servlet error dispatch`(resource: String) {
        assertEquals(HttpStatus.OK, get("/auth/me", token).statusCode)
        val response = get("/auth/account/profile-icon/$resource", token)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(404, response.body?.get("status"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["crops", "source"])
    fun `missing portrait still requires authentication`(resource: String) {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/auth/account/profile-icon/$resource").statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, get("/auth/account/profile-icon/$resource", "invalid-token").statusCode)
    }

    @Test
    fun `external request to error is not a public endpoint`() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/error").statusCode)
    }

    private fun get(path: String, bearer: String? = null) = rest.exchange(
        path, HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { bearer?.let { setBearerAuth(it) } }),
        Map::class.java,
    )

    companion object {
        private val storageRoot = Files.createTempDirectory("portrait-missing-status-")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("profile-icon.storage-root") { storageRoot.toString() }
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}
