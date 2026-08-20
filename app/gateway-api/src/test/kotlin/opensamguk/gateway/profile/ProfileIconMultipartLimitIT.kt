package opensamguk.gateway.profile

import opensamguk.common.auth.GatewayProfileClaims
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProfileIconMultipartLimitIT {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    private lateinit var token: String

    @BeforeEach
    fun resetUser() {
        userRepository.deleteAll()
        val user = userRepository.saveAndFlush(
            UserEntity(username = "multipart-limit", password = "encoded", nickname = "multipart-limit"),
        )
        token = jwtTokenProvider.generateAccessToken(
            GatewayProfileClaims(
                userId = user.id,
                username = user.username,
                role = user.role,
                nickname = user.nickname,
                grade = 1,
                picture = user.picture,
                imageServer = 0,
            ),
        )
    }

    @Test
    fun `embedded servlet enforces 51200 byte multipart file boundary before the controller`() {
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, upload(ByteArray(51_201)))
        assertEquals(HttpStatus.OK, upload(TestImageFixtures.exactSizePng(51_200)))
    }

    private fun upload(bytes: ByteArray): HttpStatusCode {
        val file = object : ByteArrayResource(bytes) {
            override fun getFilename(): String = "icon.png"
        }
        val partHeaders = HttpHeaders().apply { contentType = MediaType.IMAGE_PNG }
        val parts = LinkedMultiValueMap<String, Any>().apply {
            add("file", HttpEntity(file, partHeaders))
        }
        val requestHeaders = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(token)
        }
        return rest.exchange(
            "http://localhost:$port/auth/account/profile-icon",
            HttpMethod.POST,
            HttpEntity(parts, requestHeaders),
            String::class.java,
        ).statusCode
    }

    companion object {
        private val storageRoot: Path = Files.createTempDirectory("opensam91-multipart-")

        @JvmStatic
        @DynamicPropertySource
        fun profileIconProperties(registry: DynamicPropertyRegistry) {
            registry.add("profile-icon.storage-root") { storageRoot.toString() }
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}
