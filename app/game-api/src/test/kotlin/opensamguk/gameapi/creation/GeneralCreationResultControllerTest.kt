package opensamguk.gameapi.creation

import opensamguk.gameapi.creation.GeneralCreationErrorResponseDto
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GeneralCreationResultControllerTest {
    private val results = mock(GeneralCreationResultService::class.java)
    private val controller = GeneralCreationController(
        mock(GeneralCreationService::class.java), results, mock(GeneralCreationCatalog::class.java))
    private val requestId = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6"

    @Test fun `인증되지 않은 결과 조회는 401이며 결과 저장소를 읽지 않는다`() {
        val response = controller.result(null, requestId)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("AUTH_REQUIRED", assertIs<GeneralCreationErrorResponseDto>(response.body).error.code)
        verifyNoInteractions(results)
    }

    @Test fun `소유한 접수증이 없으면 내부 결과를 노출하지 않고 404로 응답한다`() {
        `when`(results.read(7L, requestId)).thenReturn(CreationResultProjection.NotFound)
        val response = controller.result(7L, requestId)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("CREATION_REQUEST_NOT_FOUND",
            assertIs<GeneralCreationErrorResponseDto>(response.body).error.code)
        verify(results).read(7L, requestId)
    }
}
