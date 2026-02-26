package com.opensam.service

import com.opensam.dto.NationPolicyInfo
import com.opensam.dto.OfficerInfo
import com.opensam.entity.Nation
import com.opensam.entity.WorldState
import com.opensam.repository.CityRepository
import com.opensam.repository.DiplomacyRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.repository.WorldStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
@Service
class NationService(
    private val nationRepository: NationRepository,
    private val generalRepository: GeneralRepository,
    private val officerRankService: OfficerRankService,
    private val cityRepository: CityRepository,
    private val diplomacyRepository: DiplomacyRepository,
    private val worldStateRepository: WorldStateRepository,
    private val mapService: MapService,
) {
    private val log = LoggerFactory.getLogger(NationService::class.java)
    fun listByWorld(worldId: Long): List<Nation> {
        return nationRepository.findByWorldId(worldId)
    }

    fun getById(id: Long): Nation? {
        return nationRepository.findById(id).orElse(null)
    }

    // -- Policy --

    fun getPolicy(nationId: Long): NationPolicyInfo? {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return null
        return NationPolicyInfo(
            rate = nation.rate.toInt(),
            bill = nation.bill.toInt(),
            secretLimit = nation.secretLimit.toInt(),
            strategicCmdLimit = nation.strategicCmdLimit.toInt(),
            notice = nation.meta["notice"] as? String ?: "",
            scoutMsg = nation.meta["scoutMsg"] as? String ?: "",
        )
    }

    fun updatePolicy(nationId: Long, rate: Int?, bill: Int?, secretLimit: Int?, strategicCmdLimit: Int?): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        rate?.let { nation.rate = it.toShort() }
        bill?.let { nation.bill = it.toShort() }
        secretLimit?.let { nation.secretLimit = it.toShort() }
        strategicCmdLimit?.let { nation.strategicCmdLimit = it.toShort() }
        nationRepository.save(nation)
        return true
    }

    fun updateNotice(nationId: Long, notice: String): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        nation.meta["notice"] = notice
        nationRepository.save(nation)
        return true
    }

    fun updateScoutMsg(nationId: Long, scoutMsg: String): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        nation.meta["scoutMsg"] = scoutMsg
        nationRepository.save(nation)
        return true
    }

    // -- Officers --

    fun getOfficers(nationId: Long): List<OfficerInfo> {
        val generals = generalRepository.findByNationId(nationId)
        return generals
            .filter { it.officerLevel > 0 }
            .sortedByDescending { it.officerLevel }
            .map { OfficerInfo(it.id, it.name, it.picture, it.officerLevel.toInt(), it.cityId) }
    }

    fun appointOfficer(nationId: Long, generalId: Long, officerLevel: Int, officerCity: Int?): Boolean {
        val general = generalRepository.findById(generalId).orElse(null) ?: return false
        
        // Validation checks
        if (general.nationId != nationId) {
            throw IllegalStateException("장수가 해당 국가에 속하지 않습니다.")
        }
        
        if (officerLevel < 1 || officerLevel > 12) {
            throw IllegalStateException("관직 레벨은 1-12 사이여야 합니다.")
        }
        
        if (officerLevel == 12) {
            throw IllegalStateException("군주는 유일하며 임명할 수 없습니다.")
        }
        
        if (general.officerLevel >= officerLevel) {
            throw IllegalStateException("현재 관직 레벨보다 높은 관직만 임명할 수 있습니다.")
        }
        
        general.officerLevel = officerLevel.toShort()
        if (officerCity != null) general.officerCity = officerCity
        generalRepository.save(general)
        return true
    }

    fun expelGeneral(nationId: Long, generalId: Long): Boolean {
        val general = generalRepository.findById(generalId).orElse(null) ?: return false
        if (general.nationId != nationId) return false
        general.nationId = 0
        general.officerLevel = 0
        general.troopId = 0
        generalRepository.save(general)
        return true
    }

    // -- NPC Policy --

    @Suppress("UNCHECKED_CAST")
    fun getNpcPolicy(nationId: Long): Map<String, Any>? {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return null
        val legacyPolicy = nation.meta["npcPolicy"] as? Map<String, Any> ?: emptyMap()
        val nationPolicy = nation.meta["npcNationPolicy"] as? Map<String, Any> ?: emptyMap()
        val priorityOnly = nation.meta["npcPriority"] as? Map<String, Any> ?: emptyMap()

        val merged = mutableMapOf<String, Any>()
        merged.putAll(legacyPolicy)
        merged.putAll(nationPolicy)

        if (merged["priority"] == null && priorityOnly["priority"] != null) {
            merged["priority"] = priorityOnly["priority"] as Any
        }
        return merged
    }

    fun updateNpcPolicy(nationId: Long, policy: Map<String, Any>): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        nation.meta["npcNationPolicy"] = policy
        nation.meta["npcPolicy"] = policy
        nationRepository.save(nation)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun updateNpcPriority(nationId: Long, priority: Map<String, Any>): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        val nationPolicy = (nation.meta["npcNationPolicy"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        priority["priority"]?.let { nationPolicy["priority"] = it }
        nation.meta["npcNationPolicy"] = nationPolicy
        nation.meta["npcPriority"] = priority
        nationRepository.save(nation)
        return true
    }

    /**
     * Recalculate war front status for all cities of a nation.
     * Legacy parity: SetNationFront() in func_gamerule.php
     *
     * front=0: rear (no front)
     * front=1: adjacent to imminent war city (선전포고, term<=5)
     * front=2: adjacent to neutral/empty city (peacetime only)
     * front=3: adjacent to active war city (선전포고, state=0 in legacy)
     */
    fun setNationFront(worldId: Long, nationId: Long) {
        if (nationId == 0L) return

        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return
        val mapCode = (world.config["mapCode"] as? String) ?: "che"

        val allCities = cityRepository.findByWorldId(worldId)
        val nationCities = allCities.filter { it.nationId == nationId }
        if (nationCities.isEmpty()) return

        // Get all active diplomacy where this nation is involved
        val activeDiplomacy = diplomacyRepository.findByWorldIdAndIsDeadFalse(worldId)
        val warNations = mutableSetOf<Long>()      // active war (교전): front=3
        val imminentNations = mutableSetOf<Long>()  // imminent war (선포, term<=5): front=1

        for (d in activeDiplomacy) {
            if (d.stateCode != "선전포고") continue
            val otherNationId = when {
                d.srcNationId == nationId -> d.destNationId
                d.destNationId == nationId -> d.srcNationId
                else -> continue
            }
            // Legacy: state=0 → active war (adj3/front=3), state=1 && term<=5 → imminent (adj1/front=1)
            // In Kotlin both map to "선전포고". Distinguish by term:
            // Large term (>5) with active war = adj3, small term (<=5) = adj1
            // Since scenarios start with term=975 (active war), treat term > 5 as active war.
            if (d.term > 5) {
                warNations.add(otherNationId)
            } else {
                imminentNations.add(otherNationId)
            }
        }

        // Collect adjacent city IDs for each front type
        val adj3 = mutableSetOf<Long>()  // adjacent to active war cities
        val adj1 = mutableSetOf<Long>()  // adjacent to imminent war cities
        val adj2 = mutableSetOf<Long>()  // adjacent to neutral cities (peacetime only)

        // Find cities owned by war nations, get their adjacent city IDs
        for (city in allCities) {
            if (city.nationId in warNations) {
                try {
                    val neighbors = mapService.getAdjacentCities(mapCode, city.id.toInt())
                    adj3.addAll(neighbors.map { it.toLong() })
                } catch (e: Exception) {
                    log.warn("Failed to get adjacent cities for city {}: {}", city.id, e.message)
                }
            }
            if (city.nationId in imminentNations) {
                try {
                    val neighbors = mapService.getAdjacentCities(mapCode, city.id.toInt())
                    adj1.addAll(neighbors.map { it.toLong() })
                } catch (e: Exception) {
                    log.warn("Failed to get adjacent cities for city {}: {}", city.id, e.message)
                }
            }
        }

        // Peacetime: if no war fronts, look for neutral (empty) city adjacency
        if (adj3.isEmpty() && adj1.isEmpty()) {
            for (city in allCities) {
                if (city.nationId == 0L) {
                    try {
                        val neighbors = mapService.getAdjacentCities(mapCode, city.id.toInt())
                        adj2.addAll(neighbors.map { it.toLong() })
                    } catch (e: Exception) {
                        log.warn("Failed to get adjacent cities for city {}: {}", city.id, e.message)
                    }
                }
            }
        }

        // Reset all nation cities to front=0, then set by priority (3 > 2 > 1)
        val nationCityIds = nationCities.map { it.id }.toSet()
        for (city in nationCities) {
            city.frontState = 0
        }
        // front=1 first (lowest priority)
        for (city in nationCities) {
            if (city.id in adj1) city.frontState = 1
        }
        // front=2 overwrites 1
        for (city in nationCities) {
            if (city.id in adj2) city.frontState = 2
        }
        // front=3 overwrites all (highest priority)
        for (city in nationCities) {
            if (city.id in adj3) city.frontState = 3
        }

        cityRepository.saveAll(nationCities)
        log.info("Updated front state for nation {} — adj3={}, adj1={}, adj2={}",
            nationId, adj3.intersect(nationCityIds), adj1.intersect(nationCityIds), adj2.intersect(nationCityIds))
    }

    /**
     * Recalculate front state for ALL nations in a world.
     * Called during turn processing and after scenario creation.
     */
    fun recalcAllFronts(worldId: Long) {
        val nations = nationRepository.findByWorldId(worldId).filter { it.level > 0 }
        for (nation in nations) {
            setNationFront(worldId, nation.id)
        }
    }
}
