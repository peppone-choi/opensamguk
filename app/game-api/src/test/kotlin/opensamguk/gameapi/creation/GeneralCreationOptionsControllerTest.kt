package opensamguk.gameapi.creation

import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneralCreationOptionsControllerTest {
    private val service = mock(GeneralCreationOptionsService::class.java)
    private val controller = GeneralCreationOptionsController(service)

    @Test fun `로그인하지 않은 요청은 401이다`() {
        val response = controller.options(null)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertTrue(response.body.toString().contains("AUTH_REQUIRED"))
    }

    @Test fun `선택 원천이 없으면 빈 성공 응답 대신 503이다`() {
        `when`(service.options()).thenThrow(CreationOptionsUnavailable())
        val response = controller.options(41)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertTrue(response.body.toString().contains("CREATION_POLICY_UNAVAILABLE"))
    }
}
