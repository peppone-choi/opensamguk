package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil

/**
 * JoinDrawRecorder — 장수생성(MakeGeneral) PHP-골든 게이트용 draw 기록기.
 * PHP `tools/php-golden/RandUtilDrawRecorder.php`(choiceUsingWeight 이중기록 버그픽스판)와 byte-대칭.
 *
 * MakeGeneral + SpecialityHelper.pickSpecialWar 가 호출하는 top-level 드로우 메서드
 * (nextBool / choice / nextRangeInt / choiceUsingWeight)만 오버라이드한다. 내부 nextFloat1/nextInt 는
 * 오버라이드하지 않으므로 nextBool·choiceUsingWeight 가 내부 float 를 **별도 기록하지 않아** PHP 기록기와
 * 1:1(드로우당 단일 엔트리)이 된다. 각 드로우 직전 inner DRBG 커서(stateIdx/bufferIdx)를 스냅샷해
 * byte-exact 스트림 위치까지 함께 단언한다(AiDrawRecorder/BattleDrawRecorder 패턴).
 */
class JoinDrawRecorder(private val inner: LiteHashDrbg) : RandUtil(inner) {

    data class Draw(
        val seq: Int,
        val method: String,
        val result: String,
        val stateIdxBefore: Long,
        val bufferIdxBefore: Int,
        val choiceIndex: Int? = null,
    )

    private val stream = mutableListOf<Draw>()
    private var seq = 0

    fun drawStream(): List<Draw> = stream

    private fun rec(method: String, result: String, s: Long, b: Int, choiceIndex: Int? = null) {
        stream.add(Draw(seq++, method, result, s, b, choiceIndex))
    }

    override fun nextBool(prob: Double): Boolean {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // super.nextBool 의 내부 nextFloat1 은 base RandUtil(미오버라이드)로 분기 → 별도 기록 없음(단일 엔트리).
        val r = super.nextBool(prob)
        rec("nextBool", r.toString(), s, b)
        return r
    }

    override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // RandUtil.nextRangeInt 본문을 inner 에 직접 복제(=동일 드로우).
        val r = inner.nextInt((maxInclusive - minInclusive).toLong()).toInt() + minInclusive
        rec("nextRangeInt", r.toString(), s, b)
        return r
    }

    override fun <T> choice(items: List<T>): T {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        // RandUtil.choice 본문을 복제하되 picked index 를 함께 기록.
        val idx = inner.nextInt((items.size - 1).toLong()).toInt()
        val chosen = items[idx]
        rec("choice", chosen.toString(), s, b, idx)
        return chosen
    }

    override fun choiceUsingWeight(items: Map<String, Double>): String {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // super 가 내부 nextFloat1(미오버라이드 base)을 1회 소비 → wrapper 단일 엔트리만 기록.
        val r = super.choiceUsingWeight(items)
        rec("choiceUsingWeight", r, s, b)
        return r
    }
}
