package opensamguk.gateway.controller

import opensamguk.gateway.dto.AuthResponse
import opensamguk.gateway.dto.ChangeNicknameRequest
import opensamguk.gateway.dto.ChangePasswordRequest
import opensamguk.gateway.dto.DeleteAccountRequest
import opensamguk.gateway.dto.UserResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.AuthService
import opensamguk.gateway.service.NicknameAlreadyInUseException
import opensamguk.gateway.web.GlobalExceptionHandler
import opensamguk.infra.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus

@ExtendWith(MockitoExtension::class)
class AccountControllerTest {
    @Mock lateinit var authService: AuthService

    private val user = UserEntity(id = 1, username = "tester", password = "encoded", nickname = "tester")
    private val details = CustomUserDetails(user)
    private lateinit var controller: AuthController

    @BeforeEach
    fun setUp() {
        controller = AuthController(authService)
    }

    @Test
    fun `account controller delegates authenticated mutations`() {
        assertEquals(HttpStatus.NO_CONTENT, controller.changePassword(details, ChangePasswordRequest("oldpass", "newpass1")).statusCode)
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteAccount(details, DeleteAccountRequest("oldpass")).statusCode)
        verify(authService).changePassword(details, ChangePasswordRequest("oldpass", "newpass1"))
        verify(authService).deleteAccount(details, DeleteAccountRequest("oldpass"))
    }

    @Test
    fun `nickname mutation returns replacement access and refresh tokens`() {
        val expected = AuthResponse(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            user = UserResponse(1, "tester", null, "새별명", "USER"),
        )
        org.mockito.Mockito.`when`(authService.changeNickname(details, ChangeNicknameRequest("새별명")))
            .thenReturn(expected)

        val response = controller.changeNickname(details, ChangeNicknameRequest("새별명"))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expected, response.body)
    }

    @Test
    fun `nickname duplicate maps to conflict with a Korean message`() {
        val response = GlobalExceptionHandler().nicknameAlreadyInUse(NicknameAlreadyInUseException())

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("이미 사용 중인 닉네임입니다.", response.body?.message)
    }
}
