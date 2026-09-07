package opensamguk.gateway.service

import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.OwnedGeneralReader
import opensamguk.infra.read.OwnedGeneralRow
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class RepresentativeServiceTest {
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var reader: OwnedGeneralReader

    private val user = UserEntity(id = 30, username = "tester", password = "encoded", nickname = "tester")
    private val details = CustomUserDetails(user)

    @Test
    fun `set accepts only a general the account owns and snapshots its name and world`() {
        `when`(userRepository.findByUsernameForUpdate("tester")).thenReturn(Optional.of(user))
        `when`(userRepository.saveAndFlush(user)).thenReturn(user)
        `when`(reader.findByUserId(30)).thenReturn(listOf(OwnedGeneralRow(1495, "추적w17", 1, "scenario_1010")))
        val service = RepresentativeService(userRepository, reader)

        val response = service.set(details, 1495)
        assertEquals(1495, response.current.generalId)
        assertEquals("추적w17", response.current.name)
        assertEquals(1, response.current.worldId)
        assertEquals(1, response.candidates.size)

        assertThrows(IllegalArgumentException::class.java) { service.set(details, 9999) }

        val cleared = service.set(details, null)
        assertNull(cleared.current.generalId)
        assertNull(user.representativeGeneralName)
    }

    @Test
    fun `current lists candidates from the owned-general reader`() {
        `when`(userRepository.findByUsername("tester")).thenReturn(Optional.of(user))
        `when`(reader.findByUserId(30)).thenReturn(emptyList())
        val response = RepresentativeService(userRepository, reader).current(details)
        assertNull(response.current.generalId)
        assertEquals(0, response.candidates.size)
    }
}
