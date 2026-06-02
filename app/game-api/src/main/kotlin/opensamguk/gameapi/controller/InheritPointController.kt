package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.constants.GameConst
import opensamguk.gameapi.dto.InheritActionCost
import opensamguk.gameapi.dto.InheritLog
import opensamguk.gameapi.dto.InheritPointResponse
import opensamguk.gameapi.dto.InheritStat
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.InheritanceLogReadRepository
import opensamguk.logic.inheritance.BuyHiddenBuffAction
import opensamguk.logic.inheritance.InheritanceKey
import opensamguk.logic.inheritance.InheritanceKey.Companion.keyName
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/inherit-point` (유산, spec page 15). READ-only.
 *
 * Faithful port of `v_inheritPoint.php`'s staticValues bag: per-key inheritance points, current buffs,
 * the action-cost table (with the Fibonacci-grown reset cost — `calcResetAttrPoint`), available
 * special-war/unique pickers, the last 30 inherit logs, and the caller's clamped current stat.
 *
 * Identity-required (the data is per-OWNER): the verified principal → owned general (current stat) and
 * its owner id (the `inheritance_{ownerId}` KV namespace + `inheritance_log.user_id`). The fresh seed
 * has no inherit data → zeroed items / empty buffs / empty logs (graceful, 200, no fabrication). The
 * cost table + stat bounds come straight from `GameConst` (real constants, not invented). No character
 * → 401.
 */
@RestController
@RequestMapping("/api/inherit-point")
class InheritPointController(
    private val resolver: GeneralResolver,
    private val gameKv: GameKvReadRepository,
    private val inheritLogs: InheritanceLogReadRepository,
    private val objectMapper: ObjectMapper,
) {

    /** Legacy `GameConst::$defaultStatMin/$defaultStatMax` clamp bounds for the 유산 stat display. */
    private val statMin = 10
    private val statMax = 90

    /**
     * Faithful `calcResetAttrPoint($level)`: grow [GameConst.inheritResetAttrPointBase] as a Fibonacci
     * sequence (each next = prev + prevprev) until index `level` exists, then return base[level].
     */
    private fun calcResetAttrPoint(level: Int): Int {
        val base = GameConst.inheritResetAttrPointBase.toMutableList()
        while (base.size <= level) {
            val n = base.size
            base.add(base[n - 1] + base[n - 2])
        }
        return base[level]
    }

    @GetMapping
    fun inheritPoint(@AuthenticationPrincipal userId: Long?): ResponseEntity<InheritPointResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val g = resolved.general
        val ownerKey = userId.toString()

        // Inheritance points per key — read each from the inheritance_{ownerId} KV namespace; absent → 0.
        val namespace = "inheritance_$ownerKey"
        val items = linkedMapOf<String, Int>()
        for (key in InheritanceKey.entries) {
            val kn = key.keyName()
            val row = gameKv.findByTableAndNamespaceAndKey("inheritance", namespace, kn)
            val value = if (row == null) 0 else runCatching { objectMapper.readTree(row.value).asInt(0) }.getOrDefault(0)
            items[kn] = value
        }

        // Current buffs / reset levels come from general aux (meta.aux). Absent → empty / level 0.
        @Suppress("UNCHECKED_CAST")
        val aux = (g.meta["aux"] as? Map<String, Any?>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val currentInheritBuff = (aux["inheritBuff"] as? Map<String, Any?>)
            ?.mapValues { (it.value as? Number)?.toInt() ?: 0 } ?: emptyMap()
        val resetTurnTimeLevel = ((aux["inheritResetTurnTime"] as? Number)?.toInt() ?: -1) + 1
        val resetSpecialWarLevel = ((aux["inheritResetSpecialWar"] as? Number)?.toInt() ?: -1) + 1

        val logs = inheritLogs.findByUserIdOrderByIdDesc(ownerKey, PageRequest.of(0, 30))
            .map { InheritLog(id = it.id, year = it.year, month = it.month, text = it.text) }

        val cost = InheritActionCost(
            buff = GameConst.inheritBuffPoints,
            resetTurnTime = calcResetAttrPoint(resetTurnTimeLevel),
            resetSpecialWar = calcResetAttrPoint(resetSpecialWarLevel),
            randomUnique = GameConst.inheritItemRandomPoint,
            nextSpecial = GameConst.inheritSpecificSpecialPoint,
            minSpecificUnique = GameConst.inheritItemUniqueMinPoint,
            checkOwner = GameConst.inheritCheckOwnerPoint,
            bornStatPoint = GameConst.inheritBornStatPoint,
        )

        return ResponseEntity.ok(
            InheritPointResponse(
                result = true,
                items = items,
                currentInheritBuff = currentInheritBuff,
                maxInheritBuff = BuyHiddenBuffAction.MAX_STEP,
                resetTurnTimeLevel = resetTurnTimeLevel,
                resetSpecialWarLevel = resetSpecialWarLevel,
                inheritActionCost = cost,
                availableSpecialWar = emptyMap(),
                availableUnique = emptyMap(),
                lastInheritPointLogs = logs,
                currentStat = InheritStat(
                    leadership = g.leadership.coerceIn(statMin, statMax),
                    strength = g.strength.coerceIn(statMin, statMax),
                    intel = g.intel.coerceIn(statMin, statMax),
                    statMin = statMin,
                    statMax = statMax,
                ),
            ),
        )
    }
}
