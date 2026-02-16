package com.opensam.engine

import com.opensam.entity.Diplomacy
import com.opensam.entity.WorldState
import com.opensam.repository.DiplomacyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DiplomacyService(
    private val diplomacyRepository: DiplomacyRepository,
) {
    private val log = LoggerFactory.getLogger(DiplomacyService::class.java)

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
                }
            }
        }

        diplomacyRepository.saveAll(active)
    }

    fun getRelations(worldId: Long): List<Diplomacy> {
        return diplomacyRepository.findByWorldId(worldId)
    }

    fun getRelationsForNation(worldId: Long, nationId: Long): List<Diplomacy> {
        return diplomacyRepository.findByWorldIdAndSrcNationIdOrDestNationId(worldId, nationId, nationId)
    }

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
}
