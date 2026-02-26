package com.opensam.controller

import com.opensam.engine.ai.NpcPolicyBuilder
import com.opensam.engine.ai.NpcGeneralPolicy
import com.opensam.engine.ai.NpcNationPolicy
import com.opensam.service.NationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/nations/{nationId}")
class NpcPolicyController(
    private val nationService: NationService,
) {
    @GetMapping("/npc-policy")
    fun getNpcPolicy(@PathVariable nationId: Long): ResponseEntity<Map<String, Any>> {
        val nation = nationService.getById(nationId) ?: return ResponseEntity.notFound().build()
        val mergedPolicy = nationService.getNpcPolicy(nationId) ?: emptyMap()

        val nationPolicy = NpcPolicyBuilder.buildNationPolicy(nation.meta)
        val generalPolicy = NpcPolicyBuilder.buildGeneralPolicy(nation.meta)

        val zeroPolicy = mapOf(
            "reqHumanWarUrgentGold" to nationPolicy.calcPolicyValue("reqHumanWarUrgentGold", nation),
            "reqHumanWarUrgentRice" to nationPolicy.calcPolicyValue("reqHumanWarUrgentRice", nation),
            "reqHumanWarRecommandGold" to nationPolicy.calcPolicyValue("reqHumanWarRecommandGold", nation),
            "reqHumanWarRecommandRice" to nationPolicy.calcPolicyValue("reqHumanWarRecommandRice", nation),
            "reqNPCWarGold" to nationPolicy.calcPolicyValue("reqNPCWarGold", nation),
            "reqNPCWarRice" to nationPolicy.calcPolicyValue("reqNPCWarRice", nation),
            "reqNPCDevelGold" to nationPolicy.calcPolicyValue("reqNPCDevelGold", nation),
        )

        val combatForceAsList = nationPolicy.combatForce.mapValues { listOf(it.value.first, it.value.second) }

        val response = mergedPolicy.toMutableMap()
        response["priority"] = mergedPolicy["priority"] ?: nationPolicy.priority
        response["nationPriority"] = nationPolicy.priority
        response["generalPriority"] = generalPolicy.priority
        response["currentNationPriority"] = nationPolicy.priority
        response["currentGeneralActionPriority"] = generalPolicy.priority
        response["defaultNationPriority"] = NpcNationPolicy.DEFAULT_NATION_PRIORITY
        response["defaultGeneralActionPriority"] = NpcGeneralPolicy.DEFAULT_GENERAL_PRIORITY
        response["availableNationPriorityItems"] = NpcNationPolicy.DEFAULT_NATION_PRIORITY
        response["availableGeneralActionPriorityItems"] = NpcGeneralPolicy.DEFAULT_GENERAL_PRIORITY

        response["reqNationGold"] = nationPolicy.reqNationGold
        response["reqNationRice"] = nationPolicy.reqNationRice
        response["reqHumanWarUrgentGold"] = nationPolicy.reqHumanWarUrgentGold
        response["reqHumanWarUrgentRice"] = nationPolicy.reqHumanWarUrgentRice
        response["reqHumanWarRecommandGold"] = nationPolicy.reqHumanWarRecommandGold
        response["reqHumanWarRecommandRice"] = nationPolicy.reqHumanWarRecommandRice
        response["reqHumanDevelGold"] = nationPolicy.reqHumanDevelGold
        response["reqHumanDevelRice"] = nationPolicy.reqHumanDevelRice
        response["reqNPCWarGold"] = nationPolicy.reqNPCWarGold
        response["reqNPCWarRice"] = nationPolicy.reqNPCWarRice
        response["reqNPCDevelGold"] = nationPolicy.reqNPCDevelGold
        response["reqNPCDevelRice"] = nationPolicy.reqNPCDevelRice
        response["minimumResourceActionAmount"] = nationPolicy.minimumResourceActionAmount
        response["maximumResourceActionAmount"] = nationPolicy.maximumResourceActionAmount
        response["minNPCWarLeadership"] = nationPolicy.minNPCWarLeadership
        response["minWarCrew"] = nationPolicy.minWarCrew
        response["minNPCRecruitCityPopulation"] = nationPolicy.minNPCRecruitCityPopulation
        response["safeRecruitCityPopulationRatio"] = nationPolicy.safeRecruitCityPopulationRatio
        response["properWarTrainAtmos"] = nationPolicy.properWarTrainAtmos
        response["cureThreshold"] = nationPolicy.cureThreshold

        response["combatForce"] = combatForceAsList
        response["supportForce"] = nationPolicy.supportForce
        response["developForce"] = nationPolicy.developForce
        response["CombatForce"] = combatForceAsList
        response["SupportForce"] = nationPolicy.supportForce
        response["DevelopForce"] = nationPolicy.developForce

        response["zeroPolicy"] = zeroPolicy
        response["defaultStatMax"] = 70
        response["defaultStatNPCMax"] = 60

        return ResponseEntity.ok(response)
    }

    @PutMapping("/npc-policy")
    fun updateNpcPolicy(
        @PathVariable nationId: Long,
        @RequestBody policy: Map<String, Any>,
    ): ResponseEntity<Void> {
        if (!nationService.updateNpcPolicy(nationId, policy)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PutMapping("/npc-priority")
    fun updateNpcPriority(
        @PathVariable nationId: Long,
        @RequestBody priority: Map<String, Any>,
    ): ResponseEntity<Void> {
        if (!nationService.updateNpcPriority(nationId, priority)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }
}
