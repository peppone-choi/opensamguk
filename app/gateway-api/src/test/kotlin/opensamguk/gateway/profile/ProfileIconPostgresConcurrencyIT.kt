package opensamguk.gateway.profile

import opensamguk.gateway.security.CustomUserDetails
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProfileIconPostgresConcurrencyIT {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserRepository

    private lateinit var userDetails: CustomUserDetails

    @BeforeEach
    fun resetUser() {
        userRepository.deleteAll()
        Files.list(storageRoot).use { paths ->
            paths.filter { it.fileName.toString() != ".ops" }.forEach(Files::delete)
        }
        Files.list(storageRoot.resolve(".ops")).use { paths -> paths.forEach(Files::delete) }
        val saved = userRepository.saveAndFlush(
            UserEntity(username = "concurrent-profile-icon", password = "encoded", picture = ProfileIconService.DEFAULT_ICON, nickname = "concurrent-profile-icon"),
        )
        userDetails = CustomUserDetails(saved)
    }

    @Test
    fun `two concurrent PostgreSQL upload requests produce exactly one success`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                executor.submit<Int> {
                    ready.countDown()
                    start.await(10, TimeUnit.SECONDS)
                    mockMvc.perform(
                        multipart("/auth/account/profile-icon")
                            .file(
                                MockMultipartFile(
                                    "file",
                                    "icon.png",
                                    "image/png",
                                    TestImageFixtures.image("png"),
                                ),
                            )
                            .with(user(userDetails)),
                    ).andReturn().response.status
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val statuses = requests.map { it.get(30, TimeUnit.SECONDS) }.sorted()

            assertEquals(listOf(200, 409), statuses)
            val reloaded = userRepository.findByUsername("concurrent-profile-icon").orElseThrow()
            assertTrue(reloaded.imgsvr)
            assertTrue(reloaded.profileIconManaged)
            assertTrue(Regex("[0-9a-f]{8}\\.png").matches(requireNotNull(reloaded.picture)))
            assertTrue(reloaded.profileIconChangedAt != null)
            val storedFiles = Files.list(storageRoot).use { paths ->
                paths.filter { it.fileName.toString() != ".ops" }.toList()
            }
            assertEquals(listOf(storageRoot.resolve(reloaded.picture)), storedFiles)
            assertTrue(Files.list(storageRoot.resolve(".ops")).use { paths -> paths.toList().isEmpty() })
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        private val storageRoot = Files.createTempDirectory("opensam91-postgres-")

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
