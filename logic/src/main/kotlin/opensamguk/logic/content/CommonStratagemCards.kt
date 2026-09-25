package opensamguk.logic.content

import opensamguk.logic.input.StratagemCardType

/** Headers for the already supplied common hand; instance ownership stays in StratagemHand. */
object CommonStratagemCards {
    val headers: Map<StratagemCardType, CardHeader> = mapOf(
        StratagemCardType.FORTIFY to CardHeader(
            id = "hwiha-stratagem-fortify",
            name = "견벽",
            kind = CardKind.STRATAGEM,
            availability = CardAvailability.COMMON,
            provenance = listOf(CardProvenance.Citation("三國志", "卷18")),
            renownCost = 0,
            costColors = setOf(CostColor.GRAIN),
            tags = setOf("전투", "대응"),
        ),
        StratagemCardType.INSIGHT to CardHeader(
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

    fun header(type: StratagemCardType): CardHeader = headers.getValue(type)
}
