package opensamguk.gameapi.council

import opensamguk.gameapi.read.*
import opensamguk.infra.entity.GameKvEntity
import opensamguk.logic.council.*
import opensamguk.logic.input.PoliticalInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class CouncilAuthorityReaderTest {
    private val kv = mock(GameKvReadRepository::class.java)
    private val reader = CouncilAuthorityReader(kv)
    private val world = WorldStateReadEntity(id = 1)
    private val nation = NationReadEntity(id = 3, worldId = 1,
        meta = CurrentRulerBinding.with(emptyMap(), 10, "ruler-1", PoliticalInput.RISE))
    private val people = listOf(GeneralReadEntity(id = 10, worldId = 1, nationId = 3, meta = mapOf("lord" to true)),
        GeneralReadEntity(id = 11, worldId = 1, nationId = 3))
    private val key = CouncilDesignationCodec.META_KEY
    private fun state(revoked: String? = null) = CouncilDesignationCodec.encode(CouncilDesignationState("designation-1",
        listOf(CouncilDesignation("grant-1", 3, 10, "ruler-1", 11, "grant-1", revoked))))

    @Test fun `소속 nation binding만 군주를 증명하고 부재 봉신을 빈 정원으로 선언하지 않는다`() {
        val result = reader.read(world, nation, people)
        assertEquals(setOf(10), result.readers); assertFalse(result.complete)
        assertTrue(result.designationWritable); assertNull(result.designationRevision)
        verify(kv).findByTableAndNamespaceAndKey("game_env", "game_env", key)
        verifyNoMoreInteractions(kv)
    }

    @Test fun `persisted designation이 stale world meta보다 우선하고 회수는 다음 조회에 반영된다`() {
        world.meta = mapOf(key to state())
        `when`(kv.findByTableAndNamespaceAndKey("game_env", "game_env", key)).thenReturn(
            GameKvEntity("game_env", "game_env", key, state("revoke-1"), worldId = 1))
        val result = reader.read(world, nation, people)
        assertEquals(setOf(10), result.readers); assertEquals("designation-1", result.designationRevision)
        assertFalse(result.complete)
        `when`(kv.findByTableAndNamespaceAndKey("game_env", "game_env", key)).thenReturn(null)
        assertEquals(setOf(10, 11), reader.read(world, nation, people).readers)
    }

    @Test fun `다른 world KV나 손상된 저장행은 seed fallback으로 허용을 위장하지 않는다`() {
        world.meta = mapOf(key to state())
        listOf(GameKvEntity("game_env", "game_env", key, state(), worldId = 2),
            GameKvEntity("game_env", "game_env", key, "{}", worldId = 1)).forEach { row ->
            `when`(kv.findByTableAndNamespaceAndKey("game_env", "game_env", key)).thenReturn(row)
            val result = reader.read(world, nation, people)
            assertEquals(setOf(10), result.readers); assertFalse(result.designationWritable)
            assertNull(result.designationRevision); assertFalse(result.complete)
        }
    }

    @Test fun `타국 또는 다른 world roster는 허용 계산 전에 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { reader.read(world, nation,
            people + GeneralReadEntity(id = 20, worldId = 2, nationId = 3)) }
        assertThrows(IllegalArgumentException::class.java) { reader.read(world, nation,
            people + GeneralReadEntity(id = 20, worldId = 1, nationId = 4)) }
        verifyNoInteractions(kv)
    }
}
