package opensamguk.gateway.profile

import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ProfileIconHttpSecurityTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserRepository

    private lateinit var savedUser: UserEntity

    @BeforeEach
    fun resetUser() {
        userRepository.deleteAll()
        savedUser = userRepository.saveAndFlush(
            UserEntity(username = "tester", password = "encoded", picture = ProfileIconService.DEFAULT_ICON, nickname = "tester"),
        )
    }

    @Test
    fun `unauthenticated upload and delete return json 401 with nosniff`() {
        mockMvc.perform(
            multipart(PATH).file(MockMultipartFile("file", "icon.png", "image/png", TestImageFixtures.image("png"))),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))

        mockMvc.perform(delete(PATH))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    @Test
    fun `multipart ignores spoofed client mime and filename and uses decoded AVIF`() {
        mockMvc.perform(
            multipart(PATH)
                .file(MockMultipartFile("file", "payload.php", "text/plain", TestImageFixtures.avif80()))
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.picture", matchesPattern("[0-9a-f]{8}\\.avif")))
            .andExpect(jsonPath("$.imageServer").value(1))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    @Test
    fun `oversized corrupt arbitrary and uploaded-name json inputs are rejected`() {
        mockMvc.perform(
            multipart(PATH)
                .file(MockMultipartFile("file", "large.png", "image/png", ByteArray(51_201)))
                .with(user(CustomUserDetails(savedUser))),
        ).andExpect(status().isPayloadTooLarge)

        mockMvc.perform(
            multipart(PATH)
                .file(MockMultipartFile("file", "broken.png", "image/png", byteArrayOf(1, 2, 3)))
                .with(user(CustomUserDetails(savedUser))),
        ).andExpect(status().isBadRequest)

        for (picture in listOf("../escape.png", "0123abcd.png", "https://example.test/icon.png")) {
            mockMvc.perform(
                post(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"picture\":\"$picture\",\"imgsvr\":0}")
                    .with(user(CustomUserDetails(savedUser))),
            ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `known shared icon is accepted and same-day delete after upload is denied`() {
        mockMvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"picture\":\"1001\",\"imgsvr\":0}")
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.picture").value("1001.jpg"))
            .andExpect(jsonPath("$.imageServer").value(0))

        userRepository.deleteAll()
        savedUser = userRepository.saveAndFlush(
            UserEntity(username = "tester", password = "encoded", picture = ProfileIconService.DEFAULT_ICON, nickname = "tester"),
        )
        mockMvc.perform(
            multipart(PATH)
                .file(MockMultipartFile("file", "icon.png", "image/png", TestImageFixtures.image("png")))
                .with(user(CustomUserDetails(savedUser))),
        ).andExpect(status().isOk)

        mockMvc.perform(delete(PATH).with(user(CustomUserDetails(savedUser))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value(409))
    }

    @Test
    fun `delete unlinks only the current managed upload and resets database state`() {
        val storedFileName = "0123abcd.png"
        Files.write(storageRoot.resolve(storedFileName), TestImageFixtures.image("png"))
        savedUser.picture = storedFileName
        savedUser.imgsvr = true
        savedUser.profileIconManaged = true
        savedUser = userRepository.saveAndFlush(savedUser)

        mockMvc.perform(delete(PATH).with(user(CustomUserDetails(savedUser))))
            .andExpect(status().isNoContent)

        val reloaded = userRepository.findById(savedUser.id).orElseThrow()
        assertEquals(ProfileIconService.DEFAULT_ICON, reloaded.picture)
        assertEquals(false, reloaded.imgsvr)
        assertEquals(false, reloaded.profileIconManaged)
        assertEquals(false, Files.exists(storageRoot.resolve(storedFileName)))
    }

    @Test
    fun `non-owner with duplicate legacy filename resets only itself and preserves owner file`() {
        val duplicateFileName = "89abcdef.png"
        val bytes = TestImageFixtures.image("png")
        Files.write(storageRoot.resolve(duplicateFileName), bytes)
        savedUser.picture = duplicateFileName
        savedUser.imgsvr = true
        savedUser.profileIconManaged = true
        savedUser = userRepository.saveAndFlush(savedUser)
        val nonOwner = userRepository.saveAndFlush(
            UserEntity(
                username = "duplicate-non-owner",
                password = "encoded",
                picture = duplicateFileName,
                imgsvr = true,
                profileIconManaged = false,
                nickname = "duplicate-non-owner",
            ),
        )

        mockMvc.perform(delete(PATH).with(user(CustomUserDetails(nonOwner))))
            .andExpect(status().isNoContent)

        val reloadedOwner = userRepository.findById(savedUser.id).orElseThrow()
        val reloadedNonOwner = userRepository.findById(nonOwner.id).orElseThrow()
        assertEquals(duplicateFileName, reloadedOwner.picture)
        assertEquals(true, reloadedOwner.profileIconManaged)
        assertEquals(ProfileIconService.DEFAULT_ICON, reloadedNonOwner.picture)
        assertEquals(false, reloadedNonOwner.profileIconManaged)
        assertEquals(true, Files.exists(storageRoot.resolve(duplicateFileName)))
    }

    companion object {
        private const val PATH = "/auth/account/profile-icon"
        private val storageRoot = Files.createTempDirectory("opensam91-http-")

        @JvmStatic
        @DynamicPropertySource
        fun profileIconProperties(registry: DynamicPropertyRegistry) {
            registry.add("profile-icon.storage-root") { storageRoot.toString() }
        }
    }
}
