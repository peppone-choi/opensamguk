package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.NpcPolicyResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.logic.ai.AutorunNationPolicy
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/nation/npc-policy` (NPC 정책, spec page 8). READ-only.
 *
 * Identity-required, permission >= 1 (관직자) — the caller's nation is resolved from the verified
 * principal. Returns the default policy (초깃값으로) + current policy (live `nation.meta` values) +
 * chief/general command priority lists + lastSetters audit map. Every section defaults to an empty
 * shape when the keys are absent from `nation.meta` (fresh seed has none) — graceful, never 500, no
 * fabrication. No character → 401; 재야 / insufficient permission → 403.
 */
@RestController
@RequestMapping("/api/nation")
class NpcPolicyController(
    private val resolver: GeneralResolver,
    private val nations: NationReadRepository,
) {
    @GetMapping("/npc-policy")
    fun npcPolicy(@AuthenticationPrincipal userId: Long?): ResponseEntity<NpcPolicyResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (resolved.permission < 1) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val nationId = resolved.nationId
        if (nationId == 0) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val nation = nations.findById(nationId).orElse(null)
        val meta = nation?.meta ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val currentPolicy = (meta["npc_policy"] as? Map<String, Any?>) ?: emptyMap()
        val chiefPriority = (meta["chief_priority"] as? List<*>)?.map { it.toString() } ?: emptyList()
        val generalPriority = (meta["general_priority"] as? List<*>)?.map { it.toString() } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val lastSetters = (meta["npc_policy_setters"] as? Map<String, Any?>) ?: emptyMap()

        return ResponseEntity.ok(
            NpcPolicyResponse(
                result = true,
                nationId = nationId,
                defaultPolicy = AutorunNationPolicy.DEFAULT_POLICY,
                currentPolicy = currentPolicy,
                chiefPriority = chiefPriority,
                generalPriority = generalPriority,
                lastSetters = lastSetters,
            ),
        )
    }
}
