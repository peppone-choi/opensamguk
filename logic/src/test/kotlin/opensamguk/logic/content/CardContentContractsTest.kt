package opensamguk.logic.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.logic.input.HwihaPhase
import opensamguk.logic.input.HwihaStratagemCardType
import opensamguk.logic.input.HwihaStratagemHand

class CardContentContractsTest {
    private val evidence = ContentEvidence("source-1", CardProvenance.Citation("三國志", "卷18"))
    private val card = HwihaCommonStratagemCards.header(HwihaStratagemCardType.FORTIFY)
    private val insight = HwihaCommonStratagemCards.header(HwihaStratagemCardType.INSIGHT)
    private fun bundle(
        claims: List<ContentClaim> = listOf(
            ContentClaim("claim-1", card.id, listOf(evidence.id)),
            ContentClaim("claim-2", insight.id, listOf("source-2")),
        ),
        materializations: List<ContentMaterialization> = listOf(
            ContentMaterialization("source-1", listOf(card.id)),
            ContentMaterialization("source-2", listOf(insight.id)),
        ),
    ) = CardContentBundle(setOf("source-1", "source-2"), listOf(card, insight), emptyList(), emptyList(),
        listOf(evidence, ContentEvidence("source-2", CardProvenance.GameTerm)), claims, materializations)

    @Test fun `common stratagem hand resolves to cited and game term card headers`() {
        val hand = HwihaStratagemHand.initial(1, HwihaPhase(200, 1, 1))
        val headers = (hand.hand + hand.drawPile).map { HwihaCommonStratagemCards.header(hand.cardType(it)) }
        assertTrue(headers.all { it.kind == CardKind.STRATAGEM && it.availability == CardAvailability.COMMON })
        assertEquals(listOf("三國志 卷18", "게임 용어", "三國志 卷18", "게임 용어"), headers.map { it.provenanceBadges.single() })
        assertEquals(emptyList(), CardContentValidator.violations(bundle()))
    }

    @Test fun `consumption creation and claim evidence failures stay red`() {
        val doubleUse = bundle(materializations = listOf(
            ContentMaterialization("source-1", listOf(card.id)), ContentMaterialization("source-1", listOf(insight.id)),
        ))
        assertTrue(CardContentValidator.violations(doubleUse).any { it.startsWith("duplicate consumed source") })
        assertTrue(CardContentValidator.violations(bundle(materializations = listOf(ContentMaterialization("source-1", emptyList()))))
            .any { it.startsWith("partial creation") })
        assertTrue(CardContentValidator.violations(bundle(materializations = listOf(ContentMaterialization("source-1", listOf("missing")))))
            .any { it.startsWith("dangling materialization") })
        assertTrue(CardContentValidator.violations(bundle(claims = listOf(ContentClaim("claim-1", card.id, listOf("missing")))))
            .any { it.startsWith("dangling evidence") })
    }

    @Test fun `county state has only six axes and source needs book and volume`() {
        val county = CountyStateContract(100, 20, 10, 50, 5, setOf("salt-1"))
        assertEquals(setOf("salt-1"), county.specialtyIds)
        assertFailsWith<IllegalArgumentException> { CardProvenance.Citation("三國志", "") }
        assertFailsWith<IllegalArgumentException> { CountyStateContract(-1, 20, 10, 50, 5, emptySet()) }
        assertEquals("게임 용어", CardProvenance.GameTerm.badge)
    }
}
