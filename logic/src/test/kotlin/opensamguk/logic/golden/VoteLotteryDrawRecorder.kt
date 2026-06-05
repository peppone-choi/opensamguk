package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil

/**
 * voteUnique 추첨 골든의 draw-recording 데코레이터 — [BattleDrawRecorder] 와 SYMMETRIC하나, 추첨이 실제로 쓰는
 * 두 draw 메서드(`nextBool`, `choiceUsingWeightPair`)만 기록한다.
 *
 * **왜 별도 recorder인가.** [BattleDrawRecorder] 는 전투용 메서드(nextRange/nextRangeInt/choice 등)를 기록하지만
 * `choiceUsingWeightPair` 는 오버라이드하지 않는다. 추첨 골든의 당첨 케이스는 trailing `choiceUsingWeightPair`
 * 한 draw 를 단일 엔트리로 박제했으므로, 그 한 draw 를 (내부 nextFloat1 을 ride한 채) 하나의 스트림 엔트리로
 * 기록할 recorder 가 필요하다.
 *
 * **draw 메커니즘 패러티.** 부모 [RandUtil] 의 body 를 `inner` 에 대해 직접 재현(super 호출 X)하여, `open`
 * `nextFloat1` 의 가상 디스패치로 인한 이중 로깅을 막는다. nextBool 의 단락(prob>=1 / prob<=0 / prob==0.5)도
 * 그대로 재현 — `consumed` 플래그가 실제 스트림 전진과 정확히 일치한다.
 */
class VoteLotteryDrawRecorder(private val inner: LiteHashDrbg) : RandUtil(inner) {

    /** 기록된 한 draw — PHP RandUtilDrawRecorder 스트림 엔트리의 wire-symmetric mirror. */
    data class Draw(
        val seq: Int,
        val method: String,
        val args: Map<String, Any?>,
        /** nextBool=Boolean, choiceUsingWeightPair=Pair<String,String>. */
        val result: Any?,
        val consumed: Boolean,
        val stateIdxBefore: Long,
        val bufferIdxBefore: Int,
    )

    private val stream = mutableListOf<Draw>()
    private var seq = 0

    fun drawStream(): List<Draw> = stream
    fun drawCount(): Int = stream.size

    private fun record(
        method: String,
        args: Map<String, Any?>,
        result: Any?,
        stateBefore: Long,
        bufferBefore: Int,
        consumed: Boolean,
    ) {
        stream.add(Draw(seq++, method, args, result, consumed, stateBefore, bufferBefore))
    }

    override fun nextBool(prob: Double): Boolean {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // RandUtil.nextBool 의 분기 구조를 그대로 재현 (단락은 load-bearing — 확정/불가능 prob 은 아무 것도 소비 안 함).
        if (prob >= 1) {
            record("nextBool", linkedMapOf("prob" to prob), true, s, b, false)
            return true
        }
        if (prob == 0.5) {
            val r = inner.nextBits(1)[0].toInt() != 0
            record("nextBool", linkedMapOf("prob" to prob), r, s, b, true)
            return r
        }
        if (prob <= 0) {
            record("nextBool", linkedMapOf("prob" to prob), false, s, b, false)
            return false
        }
        val r = inner.nextFloat1() < prob
        record("nextBool", linkedMapOf("prob" to prob), r, s, b, true)
        return r
    }

    override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        // RandUtil.choiceUsingWeightPair 의 body 를 inner 에 대해 직접 재현 — 단일 nextFloat1 draw + 가중 누적 스캔(<= 비교).
        var sum = 0.0; for ((_, v) in items) if (v > 0) sum += v
        var rd = inner.nextFloat1() * sum
        var chosen: T? = null
        for ((item, v) in items) {
            if (v <= 0) { if (rd <= 0) { chosen = item; break }; continue }
            if (rd <= v) { chosen = item; break }
            rd -= v
        }
        val result = chosen ?: throw IllegalStateException("Unreacheable")
        // 결과를 [type, code] 모양으로 기록 (골든의 result 배열과 대조하기 쉽게).
        record("choiceUsingWeightPair", linkedMapOf("size" to items.size), result, s, b, true)
        return result
    }
}
