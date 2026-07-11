package opensamguk.gateway.service

import opensamguk.gateway.dto.ChangePasswordRequest
import opensamguk.gateway.dto.DeleteAccountRequest
import opensamguk.gateway.dto.ProfileIconRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.BannedMemberRepository
import opensamguk.infra.read.EmailHasher
import opensamguk.infra.read.SystemFlagRepository
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AccountServiceTest {
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var jwtTokenProvider: JwtTokenProvider
    @Mock lateinit var authenticationManager: org.springframework.security.authentication.AuthenticationManager
    @Mock lateinit var systemFlagRepository: SystemFlagRepository
    @Mock lateinit var bannedMemberRepository: BannedMemberRepository

    private val encoder = BCryptPasswordEncoder()

    private fun service() = AuthService(
        userRepository,
        encoder,
        jwtTokenProvider,
        authenticationManager,
        systemFlagRepository,
        bannedMemberRepository,
        EmailHasher("goldensalt"),
    )

    private fun user() = UserEntity(id = 7, username = "tester", password = encoder.encode("oldpass"))

    private fun details(user: UserEntity) = CustomUserDetails(user)

    @Test
    fun `change password verifies current password and replaces hash`() {
        val user = user()
        `when`(userRepository.findByUsername("tester")).thenReturn(Optional.of(user))

        service().changePassword(details(user), ChangePasswordRequest("oldpass", "newpass1"))

        assertEquals(true, encoder.matches("newpass1", user.password))
        verify(userRepository).findByUsername("tester")
    }

    @Test
    fun `wrong current password is rejected`() {
        val user = user()
        `when`(userRepository.findByUsername("tester")).thenReturn(Optional.of(user))

        assertThrows(BadCredentialsException::class.java) {
            service().changePassword(details(user), ChangePasswordRequest("wrong", "newpass1"))
        }
    }

    @Test
    fun `profile icon update and delete follow picture imgsvr pair`() {
        val user = user()
        `when`(userRepository.findByUsername("tester")).thenReturn(Optional.of(user))
        val service = service()

        val updated = service.updateProfileIcon(details(user), ProfileIconRequest("icon.png", 1))
        assertEquals("icon.png", updated.picture)
        assertEquals(1, updated.imageServer)

        service.updateProfileIcon(details(user), ProfileIconRequest(null, 1))
        assertNull(user.picture)
        assertEquals(false, user.imgsvr)
    }

    @Test
    fun `account deletion verifies current password and removes user`() {
        val user = user()
        `when`(userRepository.findByUsername("tester")).thenReturn(Optional.of(user))

        service().deleteAccount(details(user), DeleteAccountRequest("oldpass"))

        verify(userRepository).delete(user)
    }
}
