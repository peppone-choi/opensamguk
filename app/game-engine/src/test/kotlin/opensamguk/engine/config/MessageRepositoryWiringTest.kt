package opensamguk.engine.config

import opensamguk.infra.read.MessageRawRepository
import opensamguk.infra.read.SideReadRepositoryConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals

class MessageRepositoryWiringTest {

    @Test
    fun `engine side-read configuration materializes one world-scoped message repository`() {
        val raw = mock(MessageRawRepository::class.java)
        `when`(raw.findMaxId(17)).thenReturn(42)

        val scope = SideReadWorldScopeConfiguration().sideReadWorldScope(EngineProcessWorld(17))
        val repository = SideReadRepositoryConfiguration().messageRepository(raw, scope)

        assertEquals(42, repository.findMaxId())
    }
}
