package opensamguk.logic.prisoner

import java.nio.ByteBuffer
import java.security.MessageDigest

enum class CaptiveInput { RELEASE_NEGOTIATION, OFFER_REPLY, ESCAPE_ATTEMPT, OTHER }

data class EscapeTerms(
    val successParts: Long? = null, val totalParts: Long? = null,
    val guardIncrease: Long? = null, val difficultyIncrease: Long? = null, val retryTicks: Long? = null,
    val quotedGuardLevel: Long? = null, val quotedDifficulty: Long? = null,
) {
    init {
        require(totalParts == null || totalParts > 0)
        require(successParts == null || successParts >= 0)
        require(totalParts == null || successParts == null || successParts <= totalParts)
        require(guardIncrease == null || guardIncrease > 0)
        require(difficultyIncrease == null || difficultyIncrease > 0)
        require(retryTicks == null || retryTicks > 0)
        require(quotedGuardLevel == null || quotedGuardLevel >= 0)
        require(quotedDifficulty == null || quotedDifficulty >= 0)
    }
}

object EscapeRules {
    fun input(state: PrisonerState, generalId: Long, input: CaptiveInput, conditionsApproved: Boolean?): PrisonerResult {
        val general = state.generals[generalId] ?: return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (general.status != GeneralCaptivity.CAPTIVE || input == CaptiveInput.OTHER) return PrisonerResult(state, PrisonerCode.ACTION_NOT_ALLOWED)
        if (!general.player || conditionsApproved == null) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        return PrisonerResult(state, if (conditionsApproved) PrisonerCode.APPLIED else PrisonerCode.ACTION_NOT_ALLOWED)
    }

    /** Threshold is an externally resolved quote for the CURRENT guard/difficulty, never a default formula. */
    fun attempt(
        state: PrisonerState, eventId: String, generalId: Long, now: Long, seed: Long, phase: Long,
        terms: EscapeTerms, conditionsApproved: Boolean?,
    ): PrisonerResult {
        val receipt = PrisonerReceipt("escape", listOf(generalId, now, seed, phase, terms).map { it.toString() })
        state.retry(eventId, receipt)?.let { return it }
        if (now < 0 || phase < 0) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        val allowed = input(state, generalId, CaptiveInput.ESCAPE_ATTEMPT, conditionsApproved)
        if (allowed.code != PrisonerCode.APPLIED) return allowed
        val general = state.generals.getValue(generalId)
        if (now < general.retryAvailableAt) return PrisonerResult(state, PrisonerCode.RETRY_WAIT)
        val quotedGuard = terms.quotedGuardLevel ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val quotedDifficulty = terms.quotedDifficulty ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (quotedGuard != general.guardLevel || quotedDifficulty != general.escapeDifficulty) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        val success = terms.successParts ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val total = terms.totalParts ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val guard = terms.guardIncrease ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val difficulty = terms.difficultyIncrease ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val retry = terms.retryTicks ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (general.attempts == Long.MAX_VALUE || guard > Long.MAX_VALUE - general.guardLevel ||
            difficulty > Long.MAX_VALUE - general.escapeDifficulty || retry > Long.MAX_VALUE - now) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        val attempt = general.attempts + 1
        val bytes = ByteBuffer.allocate(32).putLong(seed).putLong(phase).putLong(generalId).putLong(attempt).array()
        val hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).long
        val escaped = java.lang.Long.remainderUnsigned(hash, total) < success
        val next = if (escaped) general.copy(status = GeneralCaptivity.ESCAPED, attempts = attempt)
        else general.copy(guardLevel = general.guardLevel + guard, escapeDifficulty = general.escapeDifficulty + difficulty,
            retryAvailableAt = now + retry, attempts = attempt)
        return PrisonerResult(state.copy(generals = state.generals + (generalId to next)).remember(eventId, receipt), PrisonerCode.APPLIED)
    }
}
