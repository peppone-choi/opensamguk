package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus

class InstantActionControllerProfileTest {
    @Test
    fun `hwiha rejects an old instant route before code and wiring guards`() {
        val reserve = mock(CommandReserveService::class.java)
        val resolver = mock(GeneralResolver::class.java)
        val worlds = mock(WorldStateReadRepository::class.java)
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(config = mapOf("ruleProfile" to "HWIHA")))
        val response = InstantActionController(reserve, resolver, worlds).instantAction(7L, "unknownLegacyCode", 10)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("WRONG_RULE_PROFILE", (response.body as Map<*, *>)["code"])
        verifyNoInteractions(reserve)
    }
}
