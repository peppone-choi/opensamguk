package opensamguk.gateway.profile

import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
@TestPropertySource(properties = ["internal.service-token=profile-contract-test-token"])
class InternalMemberProfileControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @BeforeEach
    fun resetUsers() {
        userRepository.deleteAll()
    }

    @Test
    fun `returns the canonical four-field profile without sensitive account fields`() {
        val user = userRepository.saveAndFlush(
            UserEntity(
                username = "account-id",
                password = "encoded-password",
                email = "private@example.test",
                nickname = "display-name",
                role = "ADMIN",
                grade = 27,
                picture = "0123abcd.png",
                imgsvr = true,
            ),
        )

        mockMvc.perform(
            get("/internal/users/{id}/profile", user.id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer profile-contract-test-token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("display-name"))
            .andExpect(jsonPath("$.grade").value(9))
            .andExpect(jsonPath("$.picture").value("0123abcd.png"))
            .andExpect(jsonPath("$.imageServer").value(1))
            .andExpect(jsonPath("$.*", hasSize<Any>(4)))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.role").doesNotExist())
            .andExpect(jsonPath("$.blockUntil").doesNotExist())
    }

    @Test
    fun `uses username and admin grade fallbacks`() {
        val user = userRepository.saveAndFlush(
            UserEntity(
                username = "fallback-id",
                password = "encoded-password",
                nickname = "   ",
                role = "ADMIN",
                grade = null,
                picture = null,
                imgsvr = false,
            ),
        )

        mockMvc.perform(
            get("/internal/users/{id}/profile", user.id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer profile-contract-test-token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("fallback-id"))
            .andExpect(jsonPath("$.grade").value(6))
            .andExpect(jsonPath("$.picture").value(nullValue()))
            .andExpect(jsonPath("$.imageServer").value(0))
    }

    @Test
    fun `returns 404 for an unknown user`() {
        mockMvc.perform(
            get("/internal/users/{id}/profile", 999_999)
                .header(HttpHeaders.AUTHORIZATION, "Bearer profile-contract-test-token"),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `rejects missing and wrong service credentials`() {
        mockMvc.perform(get("/internal/users/{id}/profile", 1))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            get("/internal/users/{id}/profile", 1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejects a valid end-user access token as an internal service credential`() {
        val user = userRepository.saveAndFlush(
            UserEntity(username = "jwt-user", password = "encoded-password", nickname = "jwt-user"),
        )
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.role)

        mockMvc.perform(
            get("/internal/users/{id}/profile", user.id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
        ).andExpect(status().isUnauthorized)
    }
}
