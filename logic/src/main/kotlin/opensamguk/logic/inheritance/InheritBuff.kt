package opensamguk.logic.inheritance

import opensamguk.logic.util.clamp

/**
 * The 8-type hidden buff data class. Each field is a level 0..5; the level is multiplied by 0.01
 * when applied as a stat modifier.
 *
 * Faithful port of the TypeScript `InheritBuff` type (legacy_core2026 inheritBuff.ts).
 *
 * Buff resolution order (priority — see [InheritBuffModuleFactory.resolveAndCreate]):
 *   1. general.meta["triggerState"]["meta"]["inheritBuff"] (highest)
 *   2. general.meta["inheritBuff"] (fallback)
 *
 * @property warAvoidRatio 전투 회피율 (+level * 0.01 to self)
 * @property warCriticalRatio 전투 필살율 (+level * 0.01 to self)
 * @property warMagicTrialProb 전투 계략 시도 확률 (+level * 0.01 to self)
 * @property success 내정 성공 확률 (+level * 0.01)
 * @property fail 내정 실패 확률 (-level * 0.01, i.e. reduces fail rate)
 * @property warAvoidRatioOppose 상대 회피율 감소 (-level * 0.01 to opponent)
 * @property warCriticalRatioOppose 상대 필살율 감소 (-level * 0.01 to opponent)
 * @property warMagicTrialProbOppose 상대 계략 시도 확률 감소 (-level * 0.01 to opponent)
 */
data class InheritBuff(
    val warAvoidRatio: Int = 0,
    val warCriticalRatio: Int = 0,
    val warMagicTrialProb: Int = 0,
    val success: Int = 0,
    val fail: Int = 0,
    val warAvoidRatioOppose: Int = 0,
    val warCriticalRatioOppose: Int = 0,
    val warMagicTrialProbOppose: Int = 0,
) {
    init {
        require(warAvoidRatio in 0..5) { "warAvoidRatio must be 0..5, got $warAvoidRatio" }
        require(warCriticalRatio in 0..5) { "warCriticalRatio must be 0..5, got $warCriticalRatio" }
        require(warMagicTrialProb in 0..5) { "warMagicTrialProb must be 0..5, got $warMagicTrialProb" }
        require(success in 0..5) { "success must be 0..5, got $success" }
        require(fail in 0..5) { "fail must be 0..5, got $fail" }
        require(warAvoidRatioOppose in 0..5) { "warAvoidRatioOppose must be 0..5, got $warAvoidRatioOppose" }
        require(warCriticalRatioOppose in 0..5) { "warCriticalRatioOppose must be 0..5, got $warCriticalRatioOppose" }
        require(warMagicTrialProbOppose in 0..5) { "warMagicTrialProbOppose must be 0..5, got $warMagicTrialProbOppose" }
    }

    companion object {
        /** Read a buff level from an untyped map (e.g. meta['inheritBuff']), clamping to 0..5.
         *  NaN / Infinity → 0. */
        fun readBuffLevel(buff: Map<String, Any?>?, key: String): Int {
            if (buff == null) return 0
            val raw = buff[key] as? Number ?: return 0
            val d = raw.toDouble()
            if (d.isNaN() || d.isInfinite()) return 0
            return clamp(d, 0.0, 5.0).toInt()
        }

        /** Build an [InheritBuff] from a raw map (e.g. aux.inheritBuff or meta.inheritBuff). */
        fun fromMap(map: Map<String, Any?>?): InheritBuff {
            if (map == null) return InheritBuff()
            return InheritBuff(
                warAvoidRatio = readBuffLevel(map, "warAvoidRatio"),
                warCriticalRatio = readBuffLevel(map, "warCriticalRatio"),
                warMagicTrialProb = readBuffLevel(map, "warMagicTrialProb"),
                success = readBuffLevel(map, "success"),
                fail = readBuffLevel(map, "fail"),
                warAvoidRatioOppose = readBuffLevel(map, "warAvoidRatioOppose"),
                warCriticalRatioOppose = readBuffLevel(map, "warCriticalRatioOppose"),
                warMagicTrialProbOppose = readBuffLevel(map, "warMagicTrialProbOppose"),
            )
        }

    }
}

/** Convert this [InheritBuff] to a plain Map for storage (aux / meta).
 *  Zero-level buffs are omitted to keep storage compact. */
fun InheritBuff.toMap(): Map<String, Int> = buildMap {
    if (warAvoidRatio != 0) put("warAvoidRatio", warAvoidRatio)
    if (warCriticalRatio != 0) put("warCriticalRatio", warCriticalRatio)
    if (warMagicTrialProb != 0) put("warMagicTrialProb", warMagicTrialProb)
    if (success != 0) put("success", success)
    if (fail != 0) put("fail", fail)
    if (warAvoidRatioOppose != 0) put("warAvoidRatioOppose", warAvoidRatioOppose)
    if (warCriticalRatioOppose != 0) put("warCriticalRatioOppose", warCriticalRatioOppose)
    if (warMagicTrialProbOppose != 0) put("warMagicTrialProbOppose", warMagicTrialProbOppose)
}
