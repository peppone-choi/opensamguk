package opensamguk.gateway.config

import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.boot.ApplicationArguments
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AdminSeederTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var jdbcTemplate: JdbcTemplate
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

        AdminSeeder(userRepository, jdbcTemplate, passwordEncoder, "peppone", "new-password").run(args)

        verify(userRepository).save(existing)
        assertSame(existing, userRepository.findByUsername("peppone").orElseThrow())
        assertEquals("ADMIN", existing.role)
        assertEquals(6, existing.grade)
        assertTrue(passwordEncoder.matches("new-password", existing.password))
    }

    @Test
    fun `missing admin username is created with admin grade`() {
        `when`(userRepository.findByUsername("peppone")).thenReturn(Optional.empty())
        `when`(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString())).thenReturn(1)

        AdminSeeder(userRepository, jdbcTemplate, passwordEncoder, "peppone", "new-password").run(args)

        verify(jdbcTemplate).update(anyString(), eq("peppone"), anyString(), eq("peppone"))
    }

    @Test
    fun `missing admin uses an available fallback when its username is already another users nickname`() {
        `when`(userRepository.findByUsername("peppone")).thenReturn(Optional.empty())
        `when`(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(0, 1)

        AdminSeeder(userRepository, jdbcTemplate, passwordEncoder, "peppone", "new-password").run(args)

        verify(jdbcTemplate).update(anyString(), eq("peppone"), anyString(), eq("peppone"))
        verify(jdbcTemplate).update(anyString(), eq("peppone"), anyString(), eq("관리자"))
    }

    @Test
    fun `concurrently created admin username is promoted after the insert collision`() {
        val concurrent = UserEntity(id = 9, username = "peppone", password = "old", nickname = "관리자")
        `when`(userRepository.findByUsername("peppone"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(concurrent))
        `when`(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString())).thenReturn(0)

        AdminSeeder(userRepository, jdbcTemplate, passwordEncoder, "peppone", "new-password").run(args)

        verify(userRepository).save(concurrent)
        assertEquals("ADMIN", concurrent.role)
        assertEquals(6, concurrent.grade)
        assertTrue(passwordEncoder.matches("new-password", concurrent.password))
    }

    @Test
    fun `blank admin credentials skip seeding`() {
        AdminSeeder(userRepository, jdbcTemplate, passwordEncoder, "peppone", "").run(args)

        verify(userRepository, never()).findByUsername("peppone")
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(UserEntity::class.java))
    }
}
