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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files

@ActiveProfiles("test")
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

    /**
     * 클라이언트가 대는 mime·파일명은 무시하고 **디코드한 바이트**로 판정한다. 이제 저장본은
     * 카드 규격 JPEG 으로 다시 인코딩되므로(ProfileIconTransformer) 확장자는 항상 `.jpg` 다 —
     * 업로드가 무엇이었든 위조한 컨테이너가 그대로 흘러 들어갈 여지가 없다.
     */
    @Test
    fun `multipart ignores spoofed client mime and filename and re-encodes to a card JPEG`() {
        mockMvc.perform(
            multipart(PATH)
                .file(MockMultipartFile("file", "payload.php", "text/plain", TestImageFixtures.avif80()))
                .with(user(CustomUserDetails(savedUser))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.picture", matchesPattern("[0-9a-f]{8}\\.jpg")))
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

    @Test
    fun `manual bundle serves public variants but source and crop metadata belong only to the authenticated owner`() {
        // Larger than the legacy 50KB cap; the manual route retains the full validated source.
        val source = TestImageFixtures.exactSizePng(80_000)
        val cropsJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(PortraitBundleTest.crops())
        val result = mockMvc.perform(multipart(PATH)
            .file(MockMultipartFile("file", "original.png", "image/png", source))
            .file(MockMultipartFile("crops", "", "text/plain", cropsJson.toByteArray()))
            .with(user(CustomUserDetails(savedUser))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.picture", matchesPattern("[0-9a-f]{8}\\.portrait")))
            .andReturn()
        val picture = com.fasterxml.jackson.databind.ObjectMapper().readTree(result.response.contentAsString)["picture"].asText()
        for (variant in listOf("hero", "card", "icon")) {
            mockMvc.perform(get("/profile-icons/$picture/$variant.jpg"))
                .andExpect(status().isOk).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        }
        mockMvc.perform(get("/profile-icons/$picture/source.jpg")).andExpect(status().isNotFound)
        mockMvc.perform(get("/profile-icons/$picture")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("$PATH/source")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("$PATH/crops")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("$PATH/source").with(user(CustomUserDetails(savedUser))))
            .andExpect(status().isOk).andExpect(content().bytes(source))
            .andExpect(header().string("X-Portrait-Id", picture))
            .andExpect(header().string("Cache-Control", "private, no-store"))
        mockMvc.perform(get("$PATH/crops").with(user(CustomUserDetails(savedUser))))
            .andExpect(status().isOk).andExpect(jsonPath("$.icon.x").value(2.0 / 3))
            .andExpect(header().string("X-Portrait-Id", picture))
        val other = userRepository.saveAndFlush(UserEntity(username = "other", password = "encoded"))
        mockMvc.perform(get("$PATH/source").with(user(CustomUserDetails(other)))).andExpect(status().isNotFound)
        mockMvc.perform(multipart(PATH)
            .file(MockMultipartFile("file", "original.png", "image/png", source))
            .file(MockMultipartFile("crops", "", "text/plain", cropsJson.toByteArray()))
            .with(user(CustomUserDetails(savedUser))))
            .andExpect(status().isConflict)
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
