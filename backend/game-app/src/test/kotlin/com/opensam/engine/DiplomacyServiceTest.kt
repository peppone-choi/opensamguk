package com.opensam.engine

import com.opensam.entity.Diplomacy
import com.opensam.entity.Message
import com.opensam.entity.WorldState
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.MessageRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.Optional

class DiplomacyServiceTest {

    private lateinit var service: DiplomacyService
    private lateinit var diplomacyRepository: DiplomacyRepository
    private lateinit var messageRepository: MessageRepository

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = org.mockito.Mockito.any<T>() as T

    @BeforeEach
    fun setUp() {
        diplomacyRepository = mock(DiplomacyRepository::class.java)
        messageRepository = mock(MessageRepository::class.java)

        `when`(messageRepository.save(anyNonNull<Message>())).thenAnswer { it.arguments[0] }
        `when`(diplomacyRepository.save(anyNonNull<Diplomacy>())).thenAnswer { it.arguments[0] }
        `when`(diplomacyRepository.saveAll(anyNonNull<List<Diplomacy>>())).thenAnswer { it.arguments[0] }

        service = DiplomacyService(diplomacyRepository, messageRepository)
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
        id: Long = 0,
        srcNationId: Long = 1,
        destNationId: Long = 2,
        stateCode: String = "불가침",
        term: Short = 10,
        isDead: Boolean = false,
    ): Diplomacy {
        return Diplomacy(
            id = id,
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
        val diplomacy = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "불가침", term = 5)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(4.toShort(), diplomacy.term)
        verify(diplomacyRepository).saveAll(listOf(diplomacy))
    }

    @Test
    fun `processDiplomacyTurn marks 불가침 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "불가침", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(0.toShort(), diplomacy.term)
        assertTrue(diplomacy.isDead)
    }

    @Test
    fun `processDiplomacyTurn marks 종전제의 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "종전제의", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(0.toShort(), diplomacy.term)
        assertTrue(diplomacy.isDead)
    }

    @Test
    fun `processDiplomacyTurn does not mark 선전포고 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "선전포고", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertEquals(0.toShort(), diplomacy.term)
        assertFalse(diplomacy.isDead, "선전포고 should not auto-expire")
    }

    @Test
    fun `processDiplomacyTurn marks 불가침제의 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "불가침제의", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertTrue(diplomacy.isDead)
    }

    @Test
    fun `processDiplomacyTurn marks 불가침파기제의 as dead when term expires`() {
        val world = createWorld()
        val diplomacy = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "불가침파기제의", term = 1)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(diplomacy))

        service.processDiplomacyTurn(world)

        assertTrue(diplomacy.isDead)
    }

    @Test
    fun `processDiplomacyTurn processes multiple diplomacies`() {
        val world = createWorld()
        val d1 = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "불가침", term = 3)
        val d2 = createDiplomacy(srcNationId = 2, destNationId = 3, stateCode = "선전포고", term = 5)
        val d3 = createDiplomacy(srcNationId = 3, destNationId = 4, stateCode = "종전제의", term = 2)

        `when`(diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong()))
            .thenReturn(listOf(d1, d2, d3))

        service.processDiplomacyTurn(world)

        assertEquals(2.toShort(), d1.term)
        assertEquals(4.toShort(), d2.term)
        assertEquals(1.toShort(), d3.term)
        assertFalse(d1.isDead)
        assertFalse(d2.isDead)
        assertFalse(d3.isDead)
    }

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

    // ========== declareWar ==========

    @Test
    fun `declareWar creates 선전포고 relation`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(null)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)
        `when`(diplomacyRepository.findActiveRelationsBetween(1L, 1L, 2L)).thenReturn(emptyList())

        val result = service.declareWar(1L, 1L, 2L)

        assertEquals("선전포고", result.stateCode)
        assertEquals(Short.MAX_VALUE, result.term)
    }

    @Test
    fun `declareWar throws when non-aggression pact exists`() {
        val existingNA = createDiplomacy(stateCode = "불가침")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(existingNA)

        assertThrows(IllegalStateException::class.java) {
            service.declareWar(1L, 1L, 2L)
        }
    }

    @Test
    fun `declareWar throws when already at war`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(null)
        val existingWar = createDiplomacy(stateCode = "선전포고")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(existingWar)

        assertThrows(IllegalStateException::class.java) {
            service.declareWar(1L, 1L, 2L)
        }
    }

    @Test
    fun `declareWar kills pending proposals`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(null)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)

        val pendingProposal = createDiplomacy(stateCode = "불가침제의")
        `when`(diplomacyRepository.findActiveRelationsBetween(1L, 1L, 2L)).thenReturn(listOf(pendingProposal))

        service.declareWar(1L, 1L, 2L)

        assertTrue(pendingProposal.isDead, "Pending proposal should be killed")
    }

    // ========== proposeNonAggression ==========

    @Test
    fun `proposeNonAggression creates proposal and sends message`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(null)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침제의")).thenReturn(null)

        val result = service.proposeNonAggression(1L, 1L, 2L)

        assertEquals("불가침제의", result.stateCode)
        verify(messageRepository).save(anyNonNull<Message>())
    }

    @Test
    fun `proposeNonAggression throws when at war`() {
        val war = createDiplomacy(stateCode = "선전포고")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(war)

        assertThrows(IllegalStateException::class.java) {
            service.proposeNonAggression(1L, 1L, 2L)
        }
    }

    @Test
    fun `proposeNonAggression throws when pact already exists`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)
        val na = createDiplomacy(stateCode = "불가침")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(na)

        assertThrows(IllegalStateException::class.java) {
            service.proposeNonAggression(1L, 1L, 2L)
        }
    }

    @Test
    fun `proposeNonAggression throws when proposal already pending`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(null)
        val pending = createDiplomacy(stateCode = "불가침제의")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침제의")).thenReturn(pending)

        assertThrows(IllegalStateException::class.java) {
            service.proposeNonAggression(1L, 1L, 2L)
        }
    }

    // ========== acceptNonAggression ==========

    @Test
    fun `acceptNonAggression transitions proposal to pact`() {
        val proposal = createDiplomacy(id = 10, stateCode = "불가침제의")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침제의")).thenReturn(proposal)

        val result = service.acceptNonAggression(1L, 1L, 2L)

        assertTrue(proposal.isDead, "Proposal should be killed")
        assertEquals("불가침", result.stateCode)
        assertEquals(DiplomacyService.NON_AGGRESSION_TERM, result.term)
    }

    @Test
    fun `acceptNonAggression throws when no pending proposal`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침제의")).thenReturn(null)

        assertThrows(IllegalStateException::class.java) {
            service.acceptNonAggression(1L, 1L, 2L)
        }
    }

    // ========== proposeBreakNonAggression ==========

    @Test
    fun `proposeBreakNonAggression creates break proposal and sends message`() {
        val pact = createDiplomacy(stateCode = "불가침")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(pact)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침파기제의")).thenReturn(null)

        val result = service.proposeBreakNonAggression(1L, 1L, 2L)

        assertEquals("불가침파기제의", result.stateCode)
        verify(messageRepository).save(anyNonNull<Message>())
    }

    @Test
    fun `proposeBreakNonAggression throws when no pact exists`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(null)

        assertThrows(IllegalStateException::class.java) {
            service.proposeBreakNonAggression(1L, 1L, 2L)
        }
    }

    // ========== acceptBreakNonAggression ==========

    @Test
    fun `acceptBreakNonAggression kills proposal and pact`() {
        val proposal = createDiplomacy(id = 10, stateCode = "불가침파기제의")
        val pact = createDiplomacy(id = 5, stateCode = "불가침")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침파기제의")).thenReturn(proposal)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침")).thenReturn(pact)

        service.acceptBreakNonAggression(1L, 1L, 2L)

        assertTrue(proposal.isDead)
        assertTrue(pact.isDead)
    }

    @Test
    fun `acceptBreakNonAggression throws when no pending break proposal`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침파기제의")).thenReturn(null)

        assertThrows(IllegalStateException::class.java) {
            service.acceptBreakNonAggression(1L, 1L, 2L)
        }
    }

    // ========== proposeCeasefire ==========

    @Test
    fun `proposeCeasefire creates ceasefire proposal and sends message`() {
        val war = createDiplomacy(stateCode = "선전포고")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(war)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "종전제의")).thenReturn(null)

        val result = service.proposeCeasefire(1L, 1L, 2L)

        assertEquals("종전제의", result.stateCode)
        verify(messageRepository).save(anyNonNull<Message>())
    }

    @Test
    fun `proposeCeasefire throws when not at war`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)

        assertThrows(IllegalStateException::class.java) {
            service.proposeCeasefire(1L, 1L, 2L)
        }
    }

    @Test
    fun `proposeCeasefire throws when proposal already pending`() {
        val war = createDiplomacy(stateCode = "선전포고")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(war)
        val pending = createDiplomacy(stateCode = "종전제의")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "종전제의")).thenReturn(pending)

        assertThrows(IllegalStateException::class.java) {
            service.proposeCeasefire(1L, 1L, 2L)
        }
    }

    // ========== acceptCeasefire ==========

    @Test
    fun `acceptCeasefire kills proposal and war`() {
        val proposal = createDiplomacy(id = 10, stateCode = "종전제의")
        val war = createDiplomacy(id = 5, stateCode = "선전포고")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "종전제의")).thenReturn(proposal)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(war)

        service.acceptCeasefire(1L, 1L, 2L)

        assertTrue(proposal.isDead)
        assertTrue(war.isDead)
    }

    @Test
    fun `acceptCeasefire throws when no pending ceasefire`() {
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "종전제의")).thenReturn(null)

        assertThrows(IllegalStateException::class.java) {
            service.acceptCeasefire(1L, 1L, 2L)
        }
    }

    // ========== acceptDiplomaticMessage ==========

    @Test
    fun `acceptDiplomaticMessage routes NA proposal to acceptNonAggression`() {
        val message = Message(
            id = 100,
            worldId = 1,
            mailboxCode = "diplomacy",
            messageType = DiplomacyService.MSG_NON_AGGRESSION_PROPOSAL,
            srcId = 1,
            destId = 2,
            payload = mutableMapOf("srcNationId" to 1L, "destNationId" to 2L, "diplomacyId" to 10L),
        )
        `when`(messageRepository.findById(100L)).thenReturn(Optional.of(message))

        val proposal = createDiplomacy(id = 10, stateCode = "불가침제의")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "불가침제의")).thenReturn(proposal)

        service.acceptDiplomaticMessage(1L, 100L)

        assertTrue(proposal.isDead)
        assertEquals(true, message.meta["responded"])
        assertEquals(true, message.meta["accepted"])
    }

    @Test
    fun `acceptDiplomaticMessage routes ceasefire proposal to acceptCeasefire`() {
        val message = Message(
            id = 101,
            worldId = 1,
            mailboxCode = "diplomacy",
            messageType = DiplomacyService.MSG_CEASEFIRE_PROPOSAL,
            srcId = 1,
            destId = 2,
            payload = mutableMapOf("srcNationId" to 1L, "destNationId" to 2L, "diplomacyId" to 20L),
        )
        `when`(messageRepository.findById(101L)).thenReturn(Optional.of(message))

        val proposal = createDiplomacy(id = 20, stateCode = "종전제의")
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "종전제의")).thenReturn(proposal)
        `when`(diplomacyRepository.findActiveRelation(1L, 1L, 2L, "선전포고")).thenReturn(null)

        service.acceptDiplomaticMessage(1L, 101L)

        assertTrue(proposal.isDead)
        assertEquals(true, message.meta["accepted"])
    }

    // ========== rejectDiplomaticMessage ==========

    @Test
    fun `rejectDiplomaticMessage marks message as rejected`() {
        val message = Message(
            id = 100,
            worldId = 1,
            mailboxCode = "diplomacy",
            messageType = DiplomacyService.MSG_NON_AGGRESSION_PROPOSAL,
            payload = mutableMapOf("srcNationId" to 1L, "destNationId" to 2L),
        )
        `when`(messageRepository.findById(100L)).thenReturn(Optional.of(message))

        service.rejectDiplomaticMessage(1L, 100L)

        assertEquals(true, message.meta["responded"])
        assertEquals(false, message.meta["accepted"])
    }

    // ========== killAllRelationsForNation ==========

    @Test
    fun `killAllRelationsForNation kills all active relations`() {
        val d1 = createDiplomacy(srcNationId = 5, destNationId = 2, stateCode = "불가침")
        val d2 = createDiplomacy(srcNationId = 3, destNationId = 5, stateCode = "선전포고")
        val d3 = createDiplomacy(srcNationId = 5, destNationId = 4, stateCode = "불가침", isDead = true)
        `when`(diplomacyRepository.findByWorldIdAndSrcNationIdOrDestNationId(1L, 5L, 5L))
            .thenReturn(listOf(d1, d2, d3))

        service.killAllRelationsForNation(1L, 5L)

        assertTrue(d1.isDead)
        assertTrue(d2.isDead)
        // d3 was already dead, should stay dead
        assertTrue(d3.isDead)
    }

    // ========== getRelations ==========

    @Test
    fun `getRelations returns all diplomacies for world`() {
        val d1 = createDiplomacy(srcNationId = 1, destNationId = 2, stateCode = "불가침")
        val d2 = createDiplomacy(srcNationId = 2, destNationId = 3, stateCode = "선전포고")

        `when`(diplomacyRepository.findByWorldId(1L)).thenReturn(listOf(d1, d2))

        val relations = service.getRelations(1L)

        assertEquals(2, relations.size)
    }

    @Test
    fun `createRelation saves new diplomacy`() {
        val result = service.createRelation(1L, 1, 2, "불가침", 12)

        assertEquals("불가침", result.stateCode)
        assertEquals(12.toShort(), result.term)
        verify(diplomacyRepository).save(anyNonNull<Diplomacy>())
    }
}
