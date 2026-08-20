package opensamguk.gateway.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class NicknameChangePostgresIT {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private lateinit var savedUser: UserEntity

    @BeforeEach
    fun resetUsers() {
        userRepository.deleteAll()
        savedUser = userRepository.saveAndFlush(
            UserEntity(username = "nickname-owner", password = "encoded", nickname = "예전별명"),
        )
    }

    @Test
    fun `authenticated nickname change returns replacement tokens and the trimmed user`() {
        mockMvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nickname":"  새별명  "}""")
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.user.nickname").value("새별명"))
    }

    @Test
    fun `trimmed short nickname returns a Korean bad request`() {
        mockMvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nickname":"  한  "}""")
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("별명은 2자 이상 20자 이하여야 합니다."))
    }

    @Test
    fun `trimmed long nickname returns a Korean bad request`() {
        mockMvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(mapOf("nickname" to "  ${"가".repeat(21)}  ")))
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("별명은 2자 이상 20자 이하여야 합니다."))
    }

    @Test
    fun `case insensitive duplicate nickname returns a Korean conflict`() {
        userRepository.saveAndFlush(
            UserEntity(username = "nickname-peer", password = "encoded", nickname = "중복별명"),
        )
        mockMvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nickname":"중복별명"}""")
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("이미 사용 중인 닉네임입니다."))
    }

    @Test
    fun `concurrent nickname responses finish in database and token order`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val completion = ExecutorCompletionService<NicknameResult>(executor)
        try {
            listOf("첫별명", "둘별명").forEach { nickname ->
                completion.submit {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    val response = mockMvc.perform(
                        post(PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(mapOf("nickname" to nickname)))
                            .with(user(CustomUserDetails(savedUser))),
                    ).andExpect(status().isOk).andReturn().response
                    val body = objectMapper.readTree(response.contentAsByteArray)
                    val accessToken = body.path("accessToken").asText()
                    NicknameResult(
                        responseNickname = body.path("user").path("nickname").asText(),
                        tokenNickname = jwtTokenProvider.getProfileFromAccessToken(accessToken)?.nickname,
                    )
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val first = completion.take().get(30, TimeUnit.SECONDS)
            val last = completion.take().get(30, TimeUnit.SECONDS)
            val storedNickname = userRepository.findById(savedUser.id).orElseThrow().nickname

            assertEquals(first.responseNickname, first.tokenNickname)
            assertEquals(last.responseNickname, last.tokenNickname)
            assertEquals(storedNickname, last.responseNickname)
        } finally {
            executor.shutdownNow()
        }
    }

    private data class NicknameResult(
        val responseNickname: String,
        val tokenNickname: String?,
    )

    companion object {
        private const val PATH = "/auth/account/nickname"
        private val storageRoot = Files.createTempDirectory("opensam219-nickname-")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.postgresql.transactional-lock") { "false" }
            registry.add("profile-icon.storage-root") { storageRoot.toString() }
        }
    }
}
