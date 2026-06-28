package opensamguk.gateway.config

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.boot.ApplicationArguments
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AdminSeederTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var args: ApplicationArguments

    private val passwordEncoder = BCryptPasswordEncoder()

    @Test
    fun `existing admin username is promoted and password is refreshed`() {
        val existing = UserEntity(
            id = 7,
            username = "peppone",
            password = passwordEncoder.encode("old-password"),
            role = "USER",
            grade = 1,
        )
        `when`(userRepository.findByUsername("peppone")).thenReturn(Optional.of(existing))

        AdminSeeder(userRepository, passwordEncoder, "peppone", "new-password").run(args)

        val captor = ArgumentCaptor.forClass(UserEntity::class.java)
        verify(userRepository).save(captor.capture())
        assertSame(existing, captor.value)
        assertEquals("ADMIN", existing.role)
        assertEquals(6, existing.grade)
        assertTrue(passwordEncoder.matches("new-password", existing.password))
    }

    @Test
    fun `missing admin username is created with admin grade`() {
        `when`(userRepository.findByUsername("peppone")).thenReturn(Optional.empty())

        AdminSeeder(userRepository, passwordEncoder, "peppone", "new-password").run(args)

        val captor = ArgumentCaptor.forClass(UserEntity::class.java)
        verify(userRepository).save(captor.capture())
        val saved = captor.value
        assertEquals("peppone", saved.username)
        assertEquals("peppone", saved.nickname)
        assertEquals("ADMIN", saved.role)
        assertEquals(6, saved.grade)
        assertTrue(passwordEncoder.matches("new-password", saved.password))
    }

    @Test
    fun `blank admin credentials skip seeding`() {
        AdminSeeder(userRepository, passwordEncoder, "peppone", "").run(args)

        verify(userRepository, never()).findByUsername("peppone")
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(UserEntity::class.java))
    }
}
