package opensamguk.gateway.security

import opensamguk.gateway.dto.EnvProxyResponse
import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.service.DeployService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class AdminSatisfiedCreateReconciliationSecurityTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var deployService: DeployService

    @BeforeEach
    fun resetService() {
        reset(deployService)
    }

    @Test
    fun `unauthenticated request is rejected before reconciliation service call`() {
        mockMvc.perform(
            post(PATH, SERVER_ID, OPERATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY),
        ).andExpect(status().isUnauthorized)

        verifyNoInteractions(deployService)
    }

    @Test
    fun `non ADMIN request is rejected before reconciliation service call`() {
        mockMvc.perform(
            post(PATH, SERVER_ID, OPERATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .with(user("member").roles("USER")),
        ).andExpect(status().isForbidden)

        verifyNoInteractions(deployService)
    }

    @Test
    fun `ADMIN reaches exact satisfied CREATE reconciliation route`() {
        `when`(deployService.reconcileSatisfiedCreate(SERVER_ID, OPERATION_ID, BODY)).thenReturn(
            EnvProxyResponse(
                200,
                """{"ok":true,"reconciled":true,"completed":true,"id":"live1","operationId":"$OPERATION_ID"}""",
            ),
        )

        mockMvc.perform(
            post(PATH, SERVER_ID, OPERATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .with(user("admin").roles("ADMIN")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reconciled").value(true))

        verify(deployService).reconcileSatisfiedCreate(SERVER_ID, OPERATION_ID, BODY)
    }

    companion object {
        private const val PATH = "/admin/servers/{serverId}/operations/{operationId}/reconcile-satisfied-create"
        private const val SERVER_ID = "live1"
        private const val OPERATION_ID = "0123456789abcdef0123456789abcdef"
        private const val BODY = """{"confirm":"RECONCILE CREATE live1"}"""
    }
}
