package com.opensam.service

import com.opensam.dto.NationPolicyInfo
import com.opensam.dto.OfficerInfo
import com.opensam.entity.Nation
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import org.springframework.stereotype.Service

@Service
class NationService(
    private val nationRepository: NationRepository,
    private val generalRepository: GeneralRepository,
) {
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
        if (general.nationId != nationId) return false
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
        return nation.meta["npcPolicy"] as? Map<String, Any> ?: emptyMap()
    }

    fun updateNpcPolicy(nationId: Long, policy: Map<String, Any>): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        nation.meta["npcPolicy"] = policy
        nationRepository.save(nation)
        return true
    }

    fun updateNpcPriority(nationId: Long, priority: Map<String, Any>): Boolean {
        val nation = nationRepository.findById(nationId).orElse(null) ?: return false
        nation.meta["npcPriority"] = priority
        nationRepository.save(nation)
        return true
    }
}
