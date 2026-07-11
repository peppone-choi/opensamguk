package opensamguk.gateway.controller

import opensamguk.gateway.dto.ChangePasswordRequest
import opensamguk.gateway.dto.DeleteAccountRequest
import opensamguk.gateway.dto.ProfileIconRequest
import opensamguk.gateway.dto.UserResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.AuthService
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

    private val user = UserEntity(id = 1, username = "tester", password = "encoded")
    private val details = CustomUserDetails(user)
    private lateinit var controller: AuthController

    @BeforeEach
    fun setUp() {
        controller = AuthController(authService)
    }

    @Test
    fun `account controller delegates authenticated mutations`() {
        val response = UserResponse(1, "tester", null, null, "USER")
        org.mockito.Mockito.`when`(authService.updateProfileIcon(details, ProfileIconRequest("icon.png", 1)))
            .thenReturn(response)

        assertEquals(HttpStatus.NO_CONTENT, controller.changePassword(details, ChangePasswordRequest("oldpass", "newpass1")).statusCode)
        assertEquals(HttpStatus.OK, controller.updateProfileIcon(details, ProfileIconRequest("icon.png", 1)).statusCode)
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteAccount(details, DeleteAccountRequest("oldpass")).statusCode)
        verify(authService).changePassword(details, ChangePasswordRequest("oldpass", "newpass1"))
        verify(authService).deleteAccount(details, DeleteAccountRequest("oldpass"))
    }
}
