package opensamguk.gateway.publication

import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.publication.domain.RegisteredPublicServer
import opensamguk.gateway.publication.domain.ServerPublication
import opensamguk.gateway.publication.domain.ServerPublicationRepository
import opensamguk.gateway.publication.domain.ServerPublicationSourceUnavailable
import opensamguk.gateway.publication.domain.ServerPublicationState
import opensamguk.gateway.publication.domain.ServerPublicationTarget
import opensamguk.gateway.publication.domain.ServerPublicationWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest(properties = ["internal.service-token=publication-fixture-token"])
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class ServerPublicationHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var repository: ServerPublicationRepository
    @MockitoBean lateinit var writer: ServerPublicationWriter

    private val hidden = ServerPublication("pep", ServerPublicationState.VERIFYING, 2, ServerPublicationTarget("a".repeat(32), 0, "scenario_3190", "b".repeat(64)))

    @BeforeEach
    fun resetRepository() { reset(repository, writer) }

    @Test
    fun `anonymous public list exposes only the public whitelist and zero`() {
        `when`(repository.listPublicServers()).thenReturn(listOf(RegisteredPublicServer("uni", "통일", 0)))
        mvc.perform(get("/servers"))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("uni"))
            .andExpect(jsonPath("$[0].generation").value(0)).andExpect(jsonPath("$[0].gameUrl").value("/game/uni"))
            .andExpect(jsonPath("$[0].operationId").doesNotExist()).andExpect(jsonPath("$[0].gameApiUrl").doesNotExist())
        `when`(repository.listPublicServers()).thenReturn(listOf(RegisteredPublicServer("uni", "통일", null)))
        mvc.perform(get("/servers")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].generation").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `known empty list and source failure remain distinct`() {
        `when`(repository.listPublicServers()).thenReturn(emptyList())
        mvc.perform(get("/servers")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
        `when`(repository.listPublicServers()).thenThrow(ServerPublicationSourceUnavailable())
        mvc.perform(get("/servers")).andExpect(status().isServiceUnavailable).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.error.code").value("SERVER_LIST_UNAVAILABLE")).andExpect(jsonPath("$.servers").doesNotExist())
    }

    @Test
    fun `anonymous and ordinary member cannot read private publication admin route`() {
        mvc.perform(get("/admin/servers/pep/publication")).andExpect(status().isUnauthorized)
        mvc.perform(get("/admin/servers/pep/publication").with(user("member").roles("USER"))).andExpect(status().isForbidden)
        verifyNoInteractions(repository)
    }

    @Test
    fun `admin can read private target and generation zero without internal coordinates`() {
        `when`(repository.find("pep")).thenReturn(hidden)
        mvc.perform(get("/admin/servers/pep/publication").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state").value("VERIFYING")).andExpect(jsonPath("$.revision").value("2"))
            .andExpect(jsonPath("$.expectedGeneration").value(0)).andExpect(jsonPath("$.operationId").value("a".repeat(32)))
            .andExpect(jsonPath("$.gameApiUrl").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist())
    }

    @Test
    fun `internal admission requires actual configured service bearer even with admin authority`() {
        mvc.perform(get("/internal/servers/pep/admission")).andExpect(status().isUnauthorized)
        mvc.perform(get("/internal/servers/pep/admission").with(user("admin").roles("ADMIN"))).andExpect(status().isUnauthorized)
        mvc.perform(get("/internal/servers/pep/admission").header("Authorization", "Bearer user-token-fixture"))
            .andExpect(status().isUnauthorized)
        verifyNoInteractions(repository)
        `when`(repository.find("pep")).thenReturn(hidden)
        mvc.perform(get("/internal/servers/pep/admission").header("Authorization", "Bearer publication-fixture-token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.serverId").value("pep"))
            .andExpect(jsonPath("$.sourceStatus").value("KNOWN")).andExpect(jsonPath("$.state").value("VERIFYING"))
            .andExpect(jsonPath("$.revision").value("2")).andExpect(jsonPath("$.operationId").doesNotExist())
    }

    @Test
    fun `anonymous and member cannot perform publication CAS`() {
        val body = """{"state":"VERIFYING","expectedRevision":"1","operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expectedGeneration":0,"expectedScenarioCode":"scenario_3190","targetFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"""
        mvc.perform(put("/admin/servers/pep/publication").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        mvc.perform(put("/admin/servers/pep/publication").with(user("member").roles("USER")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        verifyNoInteractions(writer, repository)
    }

    @Test
    fun `admin malformed revision and mixed PUBLIC target are rejected before writer`() {
        val malformed = """{"state":"VERIFYING","expectedRevision":"01","operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expectedGeneration":0,"expectedScenarioCode":"scenario_3190","targetFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"""
        val mixed = """{"state":"PUBLIC","expectedRevision":"2","operationId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expectedGeneration":0,"validationReceiptSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}"""
        for (body in listOf(malformed, mixed)) {
            mvc.perform(put("/admin/servers/pep/publication").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest).andExpect(header().string("Cache-Control", "no-store"))
        }
        verifyNoInteractions(writer, repository)
    }
}
