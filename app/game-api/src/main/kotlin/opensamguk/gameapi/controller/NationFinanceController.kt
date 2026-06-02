package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.NationFinanceResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/nation/{id}/finance` (내무부, spec page 3). READ-only.
 *
 * gold/rice/level/name/color come straight from the `nation` row; the policy/budget fields
 * (income/outcome/rate/bill/secretLimit/blockWar/blockScout/warSettingCnt) and the prose
 * (nationMsg/scoutMsg) ride the `nation.meta` jsonb (the legacy SetNotice/SetScoutMsg/SetRate/SetBill/
 * SetSecretLimit/SetBlockWar/SetBlockScout setters write there). Absent keys default to a zeroed shape
 * (no fabrication). `editable` is computed from the verified principal: true only when the caller's
 * resolved general is in THIS nation with officer_level >= 5 (수뇌); anonymous → false.
 *
 * Missing nation → result:false zeroed shape (200, never 500).
 */
@RestController
@RequestMapping("/api/nation")
class NationFinanceController(
    private val nations: NationReadRepository,
    private val resolver: GeneralResolver,
) {

    @GetMapping("/{id}/finance")
    fun finance(
        @PathVariable id: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<NationFinanceResponse> {
        val nation = nations.findById(id).orElse(null)
            ?: return ResponseEntity.ok(
                NationFinanceResponse(
                    result = false, nationId = id, name = "", color = "", level = 0,
                    gold = 0, rice = 0, income = 0, outcome = 0, rate = 0, bill = 0,
                    warSettingCnt = 0, secretLimit = 0, blockWar = false, blockScout = false,
                    nationMsg = "", scoutMsg = "", editable = false,
                ),
            )
        val meta = nation.meta
        fun metaInt(key: String) = (meta[key] as? Number)?.toInt() ?: 0
        fun metaBool(key: String) = (meta[key] as? Boolean) ?: false
        fun metaText(key: String) = (meta[key] as? String) ?: ""

        val resolved = userId?.let { resolver.resolve(it) }
        val editable = resolved != null && resolved.nationId == id && resolved.officerLevel >= 5

        return ResponseEntity.ok(
            NationFinanceResponse(
                result = true,
                nationId = nation.id,
                name = nation.name,
                color = nation.color,
                level = nation.level,
                gold = nation.gold,
                rice = nation.rice,
                income = metaInt("income"),
                outcome = metaInt("outcome"),
                rate = metaInt("rate"),
                bill = metaInt("bill"),
                warSettingCnt = metaInt("war_setting_cnt"),
                secretLimit = metaInt("secretlimit"),
                blockWar = metaBool("block_war"),
                blockScout = metaBool("block_scout"),
                nationMsg = metaText("notice"),
                scoutMsg = metaText("scout_msg"),
                editable = editable,
            ),
        )
    }
}
