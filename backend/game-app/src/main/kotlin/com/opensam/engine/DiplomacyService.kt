package com.opensam.engine

import com.opensam.entity.Diplomacy
import com.opensam.entity.Message
import com.opensam.entity.WorldState
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.MessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Diplomacy state codes (legacy parity):
 * - "불가침"   = NON_AGGRESSION
 * - "선전포고" = WAR (declared / at war)
 * - "종전제의" = CEASEFIRE_PROPOSAL (pending acceptance)
 * - "불가침제의" = NON_AGGRESSION_PROPOSAL (pending acceptance)
 * - "불가침파기제의" = NON_AGGRESSION_BREAK_PROPOSAL (pending acceptance)
 */
@Service
class DiplomacyService(
    private val diplomacyRepository: DiplomacyRepository,
    private val messageRepository: MessageRepository,
) {
    private val log = LoggerFactory.getLogger(DiplomacyService::class.java)

    companion object {
        // Diplomatic message types stored in Message.messageType
        const val MSG_NON_AGGRESSION_PROPOSAL = "diplomacy_na_proposal"
        const val MSG_NON_AGGRESSION_BREAK = "diplomacy_na_break"
        const val MSG_CEASEFIRE_PROPOSAL = "diplomacy_ceasefire_proposal"

        // Default terms (in turns)
        const val NON_AGGRESSION_TERM: Short = 60  // ~5 years
        const val CEASEFIRE_PROPOSAL_TERM: Short = 12  // expires after 12 turns if not accepted
        const val NA_PROPOSAL_TERM: Short = 12
    }

    // ========== Turn processing ==========

    @Transactional
    fun processDiplomacyTurn(world: WorldState) {
        val active = diplomacyRepository.findByWorldIdAndIsDeadFalse(world.id.toLong())

        for (diplomacy in active) {
            diplomacy.term = (diplomacy.term - 1).toShort()

            if (diplomacy.term <= 0) {
                when (diplomacy.stateCode) {
                    "불가침" -> {
                        diplomacy.isDead = true
                        log.info("Non-aggression pact expired: nation {} <-> nation {}",
                            diplomacy.srcNationId, diplomacy.destNationId)
                    }
                    "선전포고" -> {
                        // War continues until ceasefire
                    }
                    "종전제의" -> {
                        diplomacy.isDead = true
                        log.info("Ceasefire proposal expired: nation {} -> nation {}",
                            diplomacy.srcNationId, diplomacy.destNationId)
                    }
                    "불가침제의" -> {
                        diplomacy.isDead = true
                        log.info("Non-aggression proposal expired: nation {} -> nation {}",
                            diplomacy.srcNationId, diplomacy.destNationId)
                    }
                    "불가침파기제의" -> {
                        diplomacy.isDead = true
                        log.info("Non-aggression break proposal expired: nation {} -> nation {}",
                            diplomacy.srcNationId, diplomacy.destNationId)
                    }
                }
            }
        }

        diplomacyRepository.saveAll(active)
    }

    // ========== Queries ==========

    fun getRelations(worldId: Long): List<Diplomacy> {
        return diplomacyRepository.findByWorldId(worldId)
    }

    fun getRelationsForNation(worldId: Long, nationId: Long): List<Diplomacy> {
        return diplomacyRepository.findByWorldIdAndSrcNationIdOrDestNationId(worldId, nationId, nationId)
    }

    fun getActiveRelation(worldId: Long, nationA: Long, nationB: Long, stateCode: String): Diplomacy? {
        return diplomacyRepository.findActiveRelation(worldId, nationA, nationB, stateCode)
    }

    // ========== State transitions ==========

    @Transactional
    fun createRelation(
        worldId: Long,
        srcNationId: Long,
        destNationId: Long,
        stateCode: String,
        term: Short,
    ): Diplomacy {
        return diplomacyRepository.save(
            Diplomacy(
                worldId = worldId,
                srcNationId = srcNationId,
                destNationId = destNationId,
                stateCode = stateCode,
                term = term,
            )
        )
    }

    /**
     * Declare war: requires no active non-aggression pact between the two nations.
     * Creates a "선전포고" relation (WAR).
     */
    @Transactional
    fun declareWar(worldId: Long, srcNationId: Long, destNationId: Long): Diplomacy {
        // Cannot declare war while non-aggression pact is active
        val existingNA = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침")
        if (existingNA != null) {
            throw IllegalStateException("Cannot declare war: non-aggression pact is active between $srcNationId and $destNationId")
        }

        // Cannot declare war if already at war
        val existingWar = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "선전포고")
        if (existingWar != null) {
            throw IllegalStateException("Already at war with nation $destNationId")
        }

        // Kill any pending proposals between these nations
        killPendingProposals(worldId, srcNationId, destNationId)

        log.info("Nation {} declares war on nation {}", srcNationId, destNationId)
        return createRelation(worldId, srcNationId, destNationId, "선전포고", Short.MAX_VALUE)
    }

    /**
     * Propose non-aggression pact. Creates a pending proposal and sends a diplomatic message.
     */
    @Transactional
    fun proposeNonAggression(worldId: Long, srcNationId: Long, destNationId: Long): Diplomacy {
        // Cannot propose if at war
        val existingWar = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "선전포고")
        if (existingWar != null) {
            throw IllegalStateException("Cannot propose non-aggression while at war with nation $destNationId")
        }

        // Cannot propose if already have a pact
        val existingNA = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침")
        if (existingNA != null) {
            throw IllegalStateException("Non-aggression pact already exists with nation $destNationId")
        }

        // Cannot propose if a proposal is already pending
        val existingProposal = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침제의")
        if (existingProposal != null) {
            throw IllegalStateException("Non-aggression proposal already pending with nation $destNationId")
        }

        val proposal = createRelation(worldId, srcNationId, destNationId, "불가침제의", NA_PROPOSAL_TERM)

        // Send diplomatic message to dest nation
        sendDiplomaticMessage(
            worldId = worldId,
            srcNationId = srcNationId,
            destNationId = destNationId,
            messageType = MSG_NON_AGGRESSION_PROPOSAL,
            diplomacyId = proposal.id,
        )

        log.info("Nation {} proposes non-aggression to nation {}", srcNationId, destNationId)
        return proposal
    }

    /**
     * Accept a non-aggression proposal. Transitions "불가침제의" -> "불가침".
     */
    @Transactional
    fun acceptNonAggression(worldId: Long, srcNationId: Long, destNationId: Long): Diplomacy {
        val proposal = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침제의")
            ?: throw IllegalStateException("No pending non-aggression proposal between $srcNationId and $destNationId")

        // Kill the proposal
        proposal.isDead = true
        diplomacyRepository.save(proposal)

        // Create active non-aggression pact
        log.info("Non-aggression pact accepted: nation {} <-> nation {}", srcNationId, destNationId)
        return createRelation(worldId, srcNationId, destNationId, "불가침", NON_AGGRESSION_TERM)
    }

    /**
     * Propose breaking a non-aggression pact. Sends a message; needs acceptance by other side.
     */
    @Transactional
    fun proposeBreakNonAggression(worldId: Long, srcNationId: Long, destNationId: Long): Diplomacy {
        val existingNA = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침")
            ?: throw IllegalStateException("No active non-aggression pact between $srcNationId and $destNationId")

        val existingProposal = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침파기제의")
        if (existingProposal != null) {
            throw IllegalStateException("Break proposal already pending with nation $destNationId")
        }

        val proposal = createRelation(worldId, srcNationId, destNationId, "불가침파기제의", NA_PROPOSAL_TERM)

        sendDiplomaticMessage(
            worldId = worldId,
            srcNationId = srcNationId,
            destNationId = destNationId,
            messageType = MSG_NON_AGGRESSION_BREAK,
            diplomacyId = proposal.id,
        )

        log.info("Nation {} proposes breaking non-aggression with nation {}", srcNationId, destNationId)
        return proposal
    }

    /**
     * Accept breaking a non-aggression pact. Kills the pact and the break proposal.
     */
    @Transactional
    fun acceptBreakNonAggression(worldId: Long, srcNationId: Long, destNationId: Long) {
        val proposal = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침파기제의")
            ?: throw IllegalStateException("No pending break proposal between $srcNationId and $destNationId")

        val pact = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "불가침")

        proposal.isDead = true
        diplomacyRepository.save(proposal)

        if (pact != null) {
            pact.isDead = true
            diplomacyRepository.save(pact)
        }

        log.info("Non-aggression pact broken: nation {} <-> nation {}", srcNationId, destNationId)
    }

    /**
     * Propose ceasefire during an active war.
     */
    @Transactional
    fun proposeCeasefire(worldId: Long, srcNationId: Long, destNationId: Long): Diplomacy {
        val existingWar = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "선전포고")
            ?: throw IllegalStateException("Not at war with nation $destNationId")

        val existingProposal = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "종전제의")
        if (existingProposal != null) {
            throw IllegalStateException("Ceasefire proposal already pending with nation $destNationId")
        }

        val proposal = createRelation(worldId, srcNationId, destNationId, "종전제의", CEASEFIRE_PROPOSAL_TERM)

        sendDiplomaticMessage(
            worldId = worldId,
            srcNationId = srcNationId,
            destNationId = destNationId,
            messageType = MSG_CEASEFIRE_PROPOSAL,
            diplomacyId = proposal.id,
        )

        log.info("Nation {} proposes ceasefire to nation {}", srcNationId, destNationId)
        return proposal
    }

    /**
     * Accept ceasefire. Ends the war and the ceasefire proposal.
     */
    @Transactional
    fun acceptCeasefire(worldId: Long, srcNationId: Long, destNationId: Long) {
        val proposal = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "종전제의")
            ?: throw IllegalStateException("No pending ceasefire proposal between $srcNationId and $destNationId")

        val war = diplomacyRepository.findActiveRelation(worldId, srcNationId, destNationId, "선전포고")

        proposal.isDead = true
        diplomacyRepository.save(proposal)

        if (war != null) {
            war.isDead = true
            diplomacyRepository.save(war)
        }

        log.info("Ceasefire accepted: nation {} <-> nation {}", srcNationId, destNationId)
    }

    /**
     * Handle accepting a diplomatic message by id. Routes to the correct acceptance method.
     */
    @Transactional
    fun acceptDiplomaticMessage(worldId: Long, messageId: Long) {
        val message = messageRepository.findById(messageId).orElseThrow {
            IllegalArgumentException("Message not found: $messageId")
        }

        val srcNationId = (message.payload["srcNationId"] as Number).toLong()
        val destNationId = (message.payload["destNationId"] as Number).toLong()

        when (message.messageType) {
            MSG_NON_AGGRESSION_PROPOSAL -> acceptNonAggression(worldId, srcNationId, destNationId)
            MSG_NON_AGGRESSION_BREAK -> acceptBreakNonAggression(worldId, srcNationId, destNationId)
            MSG_CEASEFIRE_PROPOSAL -> acceptCeasefire(worldId, srcNationId, destNationId)
            else -> throw IllegalArgumentException("Unknown diplomatic message type: ${message.messageType}")
        }

        // Mark message as responded
        message.meta["responded"] = true
        message.meta["accepted"] = true
        messageRepository.save(message)
    }

    /**
     * Handle rejecting a diplomatic message by id.
     */
    @Transactional
    fun rejectDiplomaticMessage(worldId: Long, messageId: Long) {
        val message = messageRepository.findById(messageId).orElseThrow {
            IllegalArgumentException("Message not found: $messageId")
        }

        // Mark message as responded but rejected
        message.meta["responded"] = true
        message.meta["accepted"] = false
        messageRepository.save(message)
    }

    // ========== Helpers ==========

    /**
     * Kill all pending proposals between two nations.
     */
    private fun killPendingProposals(worldId: Long, nationA: Long, nationB: Long) {
        val relations = diplomacyRepository.findActiveRelationsBetween(worldId, nationA, nationB)
        for (rel in relations) {
            if (rel.stateCode in listOf("불가침제의", "불가침파기제의", "종전제의")) {
                rel.isDead = true
            }
        }
        diplomacyRepository.saveAll(relations)
    }

    /**
     * Send a diplomatic message to the destination nation's mailbox.
     */
    private fun sendDiplomaticMessage(
        worldId: Long,
        srcNationId: Long,
        destNationId: Long,
        messageType: String,
        diplomacyId: Long,
    ) {
        messageRepository.save(
            Message(
                worldId = worldId,
                mailboxCode = "diplomacy",
                messageType = messageType,
                srcId = srcNationId,
                destId = destNationId,
                payload = mutableMapOf(
                    "srcNationId" to srcNationId,
                    "destNationId" to destNationId,
                    "diplomacyId" to diplomacyId,
                ),
            )
        )
    }

    /**
     * When a nation is destroyed, kill all its active diplomatic relations.
     */
    @Transactional
    fun killAllRelationsForNation(worldId: Long, nationId: Long) {
        val relations = diplomacyRepository.findByWorldIdAndSrcNationIdOrDestNationId(worldId, nationId, nationId)
            .filter { !it.isDead }
        for (rel in relations) {
            rel.isDead = true
        }
        diplomacyRepository.saveAll(relations)
    }
}
