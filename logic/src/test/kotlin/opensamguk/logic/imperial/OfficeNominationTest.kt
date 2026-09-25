package opensamguk.logic.imperial

import java.io.File
import opensamguk.logic.office.OfficeClaimOrigin
import opensamguk.logic.office.OfficeJurisdictionSnapshot
import opensamguk.logic.office.OfficeRules
import opensamguk.logic.office.OfficeTenure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OfficeNominationTest {
    private val draft = OfficeNomination("nom-1", 10, 20, "office.taishou", "hhs-group:109:河南尹", "有功", "later_han")

    private fun underReview() = OfficeNominationFlow.beginReview(OfficeNominationFlow.submit(draft))

    @Test
    fun `confirmed review weights aid comparison without issuing an appointment`() {
        val rules = NominationReviewRules.fromJson(File("../data/curated/han/imperial-nomination-rules.json").readText())
        assertEquals(3, rules.reviewTurnLimit)
        assertEquals(33, rules.score(setOf(NominationFactor.VACANCY_AND_CAPACITY, NominationFactor.PROPOSER_AUTHORITY)))
        assertEquals(20, rules.originStartRecognition.getValue(OfficeClaimOrigin.SELF_STYLED))
        assertEquals(60, rules.originStartRecognition.getValue(OfficeClaimOrigin.POSTHUMOUS))
        assertEquals(NominationStatus.DRAFT, draft.status)
    }

    @Test
    fun `nomination covers original lower acting defer rejection and competitor decisions`() {
        val paths = listOf(
            NominationReview(NominationReviewOutcome.ORIGINAL, 1, "office.taishou") to NominationStatus.OFFERED,
            NominationReview(NominationReviewOutcome.LOWER_OFFICE, 1, "office.xianling") to NominationStatus.OFFERED,
            NominationReview(NominationReviewOutcome.ACTING, 1, "office.taishou") to NominationStatus.OFFERED,
            NominationReview(NominationReviewOutcome.DEFER, 1) to NominationStatus.DEFERRED,
            NominationReview(NominationReviewOutcome.REJECT, 1) to NominationStatus.REJECTED,
            NominationReview(NominationReviewOutcome.COMPETITOR, 1, competitorId = 21) to NominationStatus.COMPETITOR_SELECTED,
        )
        paths.forEach { (review, status) ->
            assertEquals(status, OfficeNominationFlow.decide(underReview(), review).status)
        }
        val offered = OfficeNominationFlow.decide(underReview(), paths.first().first)
        assertEquals(NominationStatus.ACCEPTED, OfficeNominationFlow.respond(offered, true, 20).status)
        assertEquals(NominationStatus.DECLINED, OfficeNominationFlow.respond(offered, false, 20).status)
        assertFailsWith<IllegalArgumentException> { OfficeNominationFlow.respond(offered, true, 10) }
        val deferred = OfficeNominationFlow.decide(underReview(), paths[3].first)
        assertEquals(NominationStatus.SUBMITTED, OfficeNominationFlow.resubmit(deferred).status)
    }

    @Test
    fun `confirmation appends a new claim without rewriting self styled origin or tenure origin`() {
        val self = OfficeClaims.selfStyle("claim-self", "office.taishou", 20)
        val tenure = OfficeTenure("tenure-1", self.officeId, "hhs-group:109:河南尹", 20, 20, 2, OfficeClaimOrigin.SELF_STYLED, 1)
        val history = OfficeClaims.confirm(listOf(self), self.id, "claim-court", 1, CourtConfirmationProof("edict-1", self.officeId, 20, 1))
        assertEquals(2, history.size)
        assertEquals(OfficeClaimOrigin.SELF_STYLED, history[0].origin)
        assertEquals(OfficeClaimOrigin.COURT_CONFIRMED, history[1].origin)
        assertEquals(self.id, history[1].previousClaimId)
        assertEquals(ClaimRecognition.RECOGNIZED, history[1].recognitionByPolity[1])
        assertEquals(OfficeClaimOrigin.SELF_STYLED, tenure.origin)
        assertEquals(history, OfficeClaimHistoryCodec.decode(OfficeClaimHistoryCodec.encode(history)))
        assertFailsWith<IllegalArgumentException> {
            OfficeClaims.confirm(history, self.id, "claim-duplicate", 1, CourtConfirmationProof("edict-2", "office.other", 20, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            OfficeClaims.confirm(history, self.id, "claim-second", 1, CourtConfirmationProof("edict-2", self.officeId, 20, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            OfficeClaimRecord("unproved", self.officeId, 20, OfficeClaimOrigin.COURT_CONFIRMED, 1, previousClaimId = self.id)
        }
    }

    @Test
    fun `claim history codec preserves all eight origin kinds and rejects a broken chain`() {
        val history = OfficeClaimOrigin.entries.mapIndexed { index, origin ->
            OfficeClaimRecord(
                id = "claim-$index",
                officeId = "office.taishou",
                claimantId = 20,
                origin = origin,
                issuerId = 1,
                previousClaimId = if (origin == OfficeClaimOrigin.COURT_CONFIRMED) "claim-0" else null,
                edictId = if (origin == OfficeClaimOrigin.COURT_CONFIRMED) "edict-1" else null,
                recognitionByPolity = mapOf(2 to ClaimRecognition.RECOGNIZED),
            )
        }
        assertEquals(8, history.size)
        assertEquals(history, OfficeClaimHistoryCodec.decode(OfficeClaimHistoryCodec.encode(history)))
        val corrupted = history.toMutableList()
        corrupted[1] = corrupted[1].copy(previousClaimId = "missing")
        assertFailsWith<IllegalArgumentException> { OfficeClaimHistoryCodec.encode(corrupted) }
    }

    @Test
    fun `recognition and physical control are both required for effective jurisdiction`() {
        val claim = OfficeClaims.selfStyle("claim-self", "office.taishou", 20)
            .copy(recognitionByPolity = mapOf(2 to ClaimRecognition.RECOGNIZED, 3 to ClaimRecognition.REJECTED))
        val tenure = OfficeTenure("tenure-1", claim.officeId, "hhs-group:109:河南尹", 20, 20, 2, OfficeClaimOrigin.SELF_STYLED,
            appointedTurn = 1, acceptedTurn = 1, assumedTurn = 2, seatCountyId = 46)
        val snapshot = OfficeJurisdictionSnapshot(
            tenure.jurisdictionId, setOf(46), 46, 46, setOf(46), setOf(46), setOf(46), emptySet(),
        )
        val rules = OfficeRules(50, 2)
        assertEquals(listOf(46), OfficeClaims.recognizedEffectiveJurisdiction(claim, tenure, 2, snapshot, rules)?.countyIds)
        assertNull(OfficeClaims.recognizedEffectiveJurisdiction(claim, tenure, 3, snapshot, rules))
        assertNull(OfficeClaims.recognizedEffectiveJurisdiction(claim, tenure.copy(assumedTurn = null, seatCountyId = null), 2, snapshot, rules))
        assertNull(OfficeClaims.recognizedEffectiveJurisdiction(claim.copy(origin = OfficeClaimOrigin.POSTHUMOUS), tenure, 2, snapshot, rules))
    }
}
