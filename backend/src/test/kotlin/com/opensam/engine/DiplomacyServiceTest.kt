package com.opensam.engine

import com.opensam.entity.Diplomacy
import com.opensam.entity.WorldState
import com.opensam.repository.DiplomacyRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class DiplomacyServiceTest {

    private lateinit var service: DiplomacyService
    private lateinit var diplomacyRepository: DiplomacyRepository

    @BeforeEach
    fun setUp() {
        diplomacyRepository = mock(DiplomacyRepository::class.java)
        service = DiplomacyService(diplomacyRepository)
    }

    private fun createWorld(year: Short = 200, month: Short = 3): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = year,
            currentMonth = month,
            tickSeconds = 300,
        )
    }

    private fun createDiplomacy(
        srcNationId: Long,
        destNationId: Long,
        stateCode: String,
        term: Short = 10,
        isDead: Boolean = false,
    ): Diplomacy {
        return Diplomacy(
            worldId = 1,
            srcNationId = srcNationId,
            destNationId = destNationId,
            stateCode = stateCode,
            term = term,
            isDead = isDead,
        )
    }

    // ========== processDiplomacyTurn: term decrement ==========

    @Test
    fun `processDiplomacyTurn decrements term by 1`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(1, 2, "불가침", term = 5)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(4.toShort(), diplomacy.term)
        verify(diplomacyRepository).saveAll(listOf(diplomacy))
    }

    @Test
    fun `processDiplomacyTurn marks 불가침 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(1, 2, "불가침", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(0.toShort(), diplomacy.term)
        assertTrue(diplomacy.isDead)
        verify(diplomacyRepository).saveAll(listOf(diplomacy))
    }

    @Test
    fun `processDiplomacyTurn marks 종전제의 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(1, 2, "종전제의", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(0.toShort(), diplomacy.term)
        assertTrue(diplomacy.isDead)
        verify(diplomacyRepository).saveAll(listOf(diplomacy))
    }

    @Test
    fun `processDiplomacyTurn does not mark 선전포고 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(1, 2, "선전포고", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(0.toShort(), diplomacy.term)
        assertFalse(diplomacy.isDead, "선전포고 should not auto-expire")
        verify(diplomacyRepository).saveAll(listOf(diplomacy))
    }

    // ========== processDiplomacyTurn: multiple diplomacies ==========

    @Test
    fun `processDiplomacyTurn processes multiple diplomacies`() {
        val world = createWorld()
        val d1 = createDiplomacy(1, 2, "불가침", term = 3)
        val d2 = createDiplomacy(2, 3, "선전포고", term = 5)
        val d3 = createDiplomacy(3, 4, "종전제의", term = 2)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(d1, d2, d3))

        service.processDiplomacyTurn(world)

        assertEquals(2.toShort(), d1.term)
        assertEquals(4.toShort(), d2.term)
        assertEquals(1.toShort(), d3.term)
        assertFalse(d1.isDead)
        assertFalse(d2.isDead)
        assertFalse(d3.isDead)
        verify(diplomacyRepository).saveAll(listOf(d1, d2, d3))
    }

    // ========== processDiplomacyTurn: empty world ==========

    @Test
    fun `processDiplomacyTurn handles empty world gracefully`() {
        val world = createWorld()

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(emptyList())

        assertDoesNotThrow {
            service.processDiplomacyTurn(world)
        }

        verify(diplomacyRepository).saveAll(emptyList())
    }

    // ========== getRelations and create ==========

    @Test
    fun `getRelations returns all diplomacies for world`() {
        val d1 = createDiplomacy(1, 2, "불가침")
        val d2 = createDiplomacy(2, 3, "선전포고")

        `when`(diplomacyRepository.findByWorldId(1L)).thenReturn(listOf(d1, d2))

        val relations = service.getRelations(1L)

        assertEquals(2, relations.size)
        verify(diplomacyRepository).findByWorldId(1L)
    }

    @Test
    fun `createRelation saves new diplomacy`() {
        val diplomacy = createDiplomacy(1, 2, "불가침", term = 12)

        `when`(diplomacyRepository.save(any())).thenReturn(diplomacy)

        val result = service.createRelation(1L, 1, 2, "불가침", 12)

        assertEquals("불가침", result.stateCode)
        assertEquals(12.toShort(), result.term)
        verify(diplomacyRepository).save(any())
    }
}
