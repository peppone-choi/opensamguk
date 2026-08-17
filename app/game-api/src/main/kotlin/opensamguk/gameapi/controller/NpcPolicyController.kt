package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.dto.NpcPolicyLastSetter
import opensamguk.gameapi.dto.NpcPolicyResponse
import opensamguk.gameapi.dto.NpcPolicyUpdateAcceptedResponse
import opensamguk.gameapi.dto.NpcPolicyUpdateRequest
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.logic.ai.AutorunGeneralPolicy
import opensamguk.logic.ai.AutorunNationPolicy
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/nation")
class NpcPolicyController(
    private val resolver: GeneralResolver,
    private val nations: NationReadRepository,
    private val nationEnv: NationEnvReadRepository? = null,
    private val gameKv: GameKvReadRepository? = null,
    private val secretPermission: SecretPermissionReader? = null,
    private val reserve: CommandReserveService? = null,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    @GetMapping("/npc-policy")
    fun npcPolicy(@AuthenticationPrincipal userId: Long?): ResponseEntity<NpcPolicyResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (readPermission(resolved) < 1) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val nationId = resolved.nationId
        if (nationId == 0) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val nation = nations.findById(nationId).orElse(null)
        val serverNationPolicy = gameKvRoot("npc_nation_policy")
        val serverGeneralPolicy = gameKvRoot("npc_general_policy")
        val nationPolicy = nationEnvRoot(nationId, "npc_nation_policy")
        val generalPolicy = nationEnvRoot(nationId, "npc_general_policy")
        val defaultNationPolicy = mergePolicyValues(serverNationPolicy, AutorunNationPolicy.DEFAULT_POLICY)
        val currentNationPolicy = mergePolicyValues(nationPolicy, defaultNationPolicy)
        val defaultNationPriority = stringList(serverNationPolicy["priority"]) ?: AutorunNationPolicy.DEFAULT_PRIORITY
        val currentNationPriority = stringList(nationPolicy["priority"]) ?: defaultNationPriority
        val defaultGeneralPriority = stringList(serverGeneralPolicy["priority"]) ?: AutorunGeneralPolicy.DEFAULT_PRIORITY
        val currentGeneralPriority = stringList(generalPolicy["priority"]) ?: defaultGeneralPriority
        val zeroPolicy = zeroPolicy(nation?.tech?.toInt() ?: 0, gameEnvInt("develcost"))

        return ResponseEntity.ok(
            NpcPolicyResponse(
                result = true,
                nationId = nationId,
                defaultNationPolicy = defaultNationPolicy,
                currentNationPolicy = currentNationPolicy,
                zeroPolicy = zeroPolicy,
                defaultNationPriority = defaultNationPriority,
                currentNationPriority = currentNationPriority,
                availableNationPriorityItems = AutorunNationPolicy.DEFAULT_PRIORITY,
                defaultGeneralActionPriority = defaultGeneralPriority,
                currentGeneralActionPriority = currentGeneralPriority,
                availableGeneralActionPriorityItems = AutorunGeneralPolicy.DEFAULT_PRIORITY,
                lastSetters = lastSetters(nationPolicy, generalPolicy),
                defaultStatNPCMax = AutorunNationPolicy.DEFAULT_STAT_NPC_MAX,
                defaultStatMax = AutorunNationPolicy.DEFAULT_STAT_MAX,
            ),
        )
    }

    @PostMapping("/npc-policy")
    fun updateNpcPolicy(
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: NpcPolicyUpdateRequest,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (resolved.nationId == 0) return blocked("국가에 소속되어있지 않습니다.")
        if (readPermission(resolved) < 3) return blocked("권한이 부족합니다. 군주, 외교권자, 조언자가 아닙니다.")
        val data = body.data ?: return blocked("올바른 입력이 아닙니다.")
        if (body.type !in setOf("nationPolicy", "nationPriority", "generalPriority")) {
            return blocked("올바른 타입이 아닙니다.")
        }
        val service = reserve ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        val accepted = service.publishImmediate(
            TurnDaemonCommand.NpcPolicyUpdate(
                generalId = resolved.general.id,
                policyType = body.type,
                data = Json.parseToJsonElement(objectMapper.writeValueAsString(data)),
            ),
            // OPENSAM-197 — result-read ownership witness (this path records no general_id).
            ownerUserId = userId.toInt(),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(NpcPolicyUpdateAcceptedResponse(status = "AVAILABLE", requestId = accepted.requestId, code = "npcPolicyUpdate"))
    }

    private fun readPermission(resolved: GeneralResolver.ResolvedGeneral): Int =
        secretPermission?.of(resolved) ?: resolved.permission

    private fun blocked(reason: String): ResponseEntity<Any> =
        ResponseEntity.ok(mapOf("status" to "BLOCKED", "reason" to reason))

    private fun gameKvRoot(key: String): Map<String, Any?> =
        decodeMap(gameKv?.findByTableAndNamespaceAndKey("game_env", "game_env", key)?.value)

    private fun nationEnvRoot(nationId: Int, key: String): Map<String, Any?> =
        decodeMap(nationEnv?.findByNamespaceAndKey(nationId, key)?.value)

    private fun gameEnvInt(key: String): Int =
        when (val value = decodeAny(gameKv?.findByTableAndNamespaceAndKey("game_env", "game_env", key)?.value)) {
            is Number -> value.toInt()
            else -> 0
        }

    private fun mergePolicyValues(root: Map<String, Any?>, defaults: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        mapValue(root["values"])?.forEach { (k, v) -> out[k] = v }
        defaults.forEach { (k, v) -> out.putIfAbsent(k, v) }
        return out
    }

    private fun lastSetters(
        nationPolicy: Map<String, Any?>,
        generalPolicy: Map<String, Any?>,
    ): Map<String, NpcPolicyLastSetter> = linkedMapOf(
        "policy" to NpcPolicyLastSetter(nationPolicy["valueSetter"] as? String, nationPolicy["valueSetTime"] as? String),
        "nation" to NpcPolicyLastSetter(nationPolicy["prioritySetter"] as? String, nationPolicy["prioritySetTime"] as? String),
        "general" to NpcPolicyLastSetter(generalPolicy["prioritySetter"] as? String, generalPolicy["prioritySetTime"] as? String),
    )

    private fun zeroPolicy(tech: Int, develcost: Int): LinkedHashMap<String, Any?> {
        val policy = AutorunNationPolicy(npcType = 2, tech = tech, develcost = develcost)
        return linkedMapOf(
            "reqNationGold" to policy.reqNationGold,
            "reqNationRice" to policy.reqNationRice,
            "CombatForce" to policy.combatForce,
            "SupportForce" to policy.supportForce,
            "DevelopForce" to policy.developForce,
            "reqHumanWarUrgentGold" to policy.reqHumanWarUrgentGold,
            "reqHumanWarUrgentRice" to policy.reqHumanWarUrgentRice,
            "reqHumanWarRecommandGold" to policy.reqHumanWarRecommandGold,
            "reqHumanWarRecommandRice" to policy.reqHumanWarRecommandRice,
            "reqHumanDevelGold" to policy.reqHumanDevelGold,
            "reqHumanDevelRice" to policy.reqHumanDevelRice,
            "reqNPCWarGold" to policy.reqNPCWarGold,
            "reqNPCWarRice" to policy.reqNPCWarRice,
            "reqNPCDevelGold" to policy.reqNPCDevelGold,
            "reqNPCDevelRice" to policy.reqNPCDevelRice,
            "minimumResourceActionAmount" to policy.minimumResourceActionAmount,
            "maximumResourceActionAmount" to policy.maximumResourceActionAmount,
            "minNPCWarLeadership" to policy.minNPCWarLeadership,
            "minWarCrew" to policy.minWarCrew,
            "minNPCRecruitCityPopulation" to policy.minNPCRecruitCityPopulation,
            "safeRecruitCityPopulationRatio" to policy.safeRecruitCityPopulationRatio,
            "properWarTrainAtmos" to policy.properWarTrainAtmos,
            "cureThreshold" to policy.cureThreshold,
        )
    }

    private fun decodeMap(raw: String?): Map<String, Any?> =
        mapValue(decodeAny(raw)) ?: emptyMap()

    private fun decodeAny(raw: String?): Any? =
        raw?.let { runCatching { objectMapper.readValue(it, Any::class.java) }.getOrNull() }

    private fun mapValue(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { (k, v) -> k.toString() to v }

    private fun stringList(value: Any?): List<String>? =
        (value as? List<*>)?.map { it.toString() }
}
