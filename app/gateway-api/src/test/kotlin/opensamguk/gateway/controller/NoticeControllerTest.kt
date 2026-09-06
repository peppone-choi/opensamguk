package opensamguk.gateway.controller

import opensamguk.gateway.dto.NoticePinRequest
import opensamguk.gateway.dto.NoticeResponse
import opensamguk.gateway.dto.NoticeUpsertRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.NoticeService
import opensamguk.infra.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus

@ExtendWith(MockitoExtension::class)
class NoticeControllerTest {
    @Mock lateinit var notices: NoticeService

    private val sample = NoticeResponse(id = 1, title = "t", body = "b", pinned = false, publishedAt = "2026-09-06T00:00:00Z", deleted = false)

    @Test
    fun `public feed uses the default limit when none is given`() {
        `when`(notices.publicFeed(NoticeService.PUBLIC_LIMIT)).thenReturn(listOf(sample))
        val response = NoticeController(notices).publicFeed(null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body?.notices?.size)
    }

    @Test
    fun `admin mutations delegate with the acting account id`() {
        val admin = CustomUserDetails(UserEntity(id = 42, username = "peppone", password = "x", nickname = "peppone", role = "ADMIN"))
        val request = NoticeUpsertRequest("t", "b", pinned = true)
        `when`(notices.create(request, 42)).thenReturn(sample)
        `when`(notices.setPinned(1, false)).thenReturn(sample)
        `when`(notices.delete(1)).thenReturn(sample.copy(deleted = true))

        val controller = NoticeController(notices)
        assertEquals(HttpStatus.OK, controller.create(admin, request).statusCode)
        assertEquals(HttpStatus.OK, controller.pin(1, NoticePinRequest(false)).statusCode)
        assertEquals(true, controller.delete(1).body?.deleted)
        verify(notices).create(request, 42)
    }
}
