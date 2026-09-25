package opensamguk.logic.content

import opensamguk.logic.input.HwihaStratagemCardType

/** Headers for the already supplied common hand; instance ownership stays in HwihaStratagemHand. */
object HwihaCommonStratagemCards {
    val headers: Map<HwihaStratagemCardType, CardHeader> = mapOf(
        HwihaStratagemCardType.FORTIFY to CardHeader(
            id = "hwiha-stratagem-fortify",
            name = "견벽",
            kind = CardKind.STRATAGEM,
            availability = CardAvailability.COMMON,
            provenance = listOf(CardProvenance.Citation("三國志", "卷18")),
            renownCost = 0,
            costColors = setOf(CostColor.GRAIN),
            tags = setOf("전투", "대응"),
        ),
        HwihaStratagemCardType.INSIGHT to CardHeader(
            id = "hwiha-stratagem-insight",
            name = "간파",
            kind = CardKind.STRATAGEM,
            availability = CardAvailability.COMMON,
            provenance = listOf(CardProvenance.GameTerm),
            renownCost = 0,
            costColors = setOf(CostColor.MONEY),
            tags = setOf("전투", "대응"),
        ),
    )

    fun header(type: HwihaStratagemCardType): CardHeader = headers.getValue(type)
}
