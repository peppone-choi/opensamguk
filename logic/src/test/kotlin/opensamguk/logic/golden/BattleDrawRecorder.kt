package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil

/**
 * P4 G1 — the Kotlin draw-recording harness, SYMMETRIC to the PHP `tools/php-golden/RandUtilDrawRecorder.php`.
 *
 * A faithful, DRAW-NEUTRAL decorator over the game's [RandUtil]. It records the ORDERED draw stream produced
 * by a battle's single shared `RandUtil(LiteHashDrbg(warSeed))` — the load-bearing P4 parity surface (every
 * nextBool / choice / nextRange / nextRangeInt / nextInt / nextBit in the exact ORDER it is pulled off the ONE
 * stream).
 *
 * **Why a subclass, not a wrapper.** `WarUnitGeneral` / `WarUnitCity` take a `RandUtil` and thread it BY
 * REFERENCE into every trigger fire. To observe EVERY draw without changing any call site the recorder MUST be
 * an `is RandUtil`. We extend [RandUtil] and override each draw method to (1) snapshot the underlying
 * [LiteHashDrbg] cursor (stateIdx/bufferIdx) BEFORE the draw, (2) consume EXACTLY ONE logical draw off the same
 * inner DRBG (byte-identical to bare [RandUtil] — we replicate the parent's body against [inner] rather than
 * call `super`, so the `open` `nextFloat1` is never double-dispatched and no draw is double-logged), (3) log
 * `{seq, method, args, result, consumed, stateIdxBefore, bufferIdxBefore}`.
 *
 * **Cursor fingerprint.** [LiteHashDrbg.peekStateIdx] (the SHA-512 block counter, advanced by genNextBlock) +
 * [LiteHashDrbg.peekBufferIdx] (the byte offset within the current 64-byte block) are the byte-exact stream
 * position — the SAME `stateIdx`/`bufferIdx` the PHP recorder reflects, so the gate asserts value-for-value AND
 * cursor-for-cursor at the first divergent draw.
 *
 * **Short-circuit fidelity (load-bearing).** `nextBool(prob>=1)` ⇒ true / `nextBool(prob<=0)` ⇒ false consume
 * NO draw; recorded with `consumed=false`, cursor unchanged — exactly the PHP recorder's behavior.
 */
class BattleDrawRecorder(private val inner: LiteHashDrbg) : RandUtil(inner) {

    /** One recorded draw — the wire-symmetric mirror of the PHP RandUtilDrawRecorder stream entry. */
    data class Draw(
        val seq: Int,
        val method: String,
        /** args verbatim: {prob} for nextBool, {min,max} for nextRange/nextRangeInt, {items} for choice. */
        val args: Map<String, Any?>,
        val result: Any?,
        val consumed: Boolean,
        val stateIdxBefore: Long,
        val bufferIdxBefore: Int,
        /** Only set for `choice`: the nextInt INDEX (the cursor-load-bearing integer). */
        val choiceIndex: Int? = null,
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
        choiceIndex: Int? = null,
    ) {
        stream.add(Draw(seq++, method, args, result, consumed, stateBefore, bufferBefore, choiceIndex))
    }

    // ── overridden draw methods (snapshot cursor BEFORE → consume one inner draw → log) ──────────────────

    override fun nextFloat1(): Double {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = inner.nextFloat1()
        record("nextFloat1", emptyMap(), r, s, b, true)
        return r
    }

    override fun nextRange(min: Double, max: Double): Double {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // RandUtil.nextRange = nextFloat1()*(max-min)+min — ONE float draw. Replicate against `inner`
        // directly (NOT super, which would virtual-dispatch the overridden nextFloat1 and double-log).
        val r = inner.nextFloat1() * (max - min) + min
        record("nextRange", linkedMapOf("min" to min, "max" to max), r, s, b, true)
        return r
    }

    override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // RandUtil.nextRangeInt = rng.nextInt((max-min)).toInt() + min — ONE int draw.
        val r = inner.nextInt((maxInclusive - minInclusive).toLong()).toInt() + minInclusive
        record("nextRangeInt", linkedMapOf("min" to minInclusive, "max" to maxInclusive), r, s, b, true)
        return r
    }

    override fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // RandUtil.nextInt: span<=1 ⇒ min (NO draw); else min + rng.nextInt(span-1).
        val span = maxExclusive - minInclusive
        if (span <= 1) {
            record("nextInt", linkedMapOf("min" to minInclusive, "max" to maxExclusive), minInclusive, s, b, false)
            return minInclusive
        }
        val r = minInclusive + inner.nextInt((span - 1).toLong()).toInt()
        record("nextInt", linkedMapOf("min" to minInclusive, "max" to maxExclusive), r, s, b, true)
        return r
    }

    override fun nextBit(): Boolean {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = inner.nextBits(1)[0].toInt() != 0
        record("nextBit", emptyMap(), r, s, b, true)
        return r
    }

    override fun nextBool(prob: Double): Boolean {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        // Reproduce RandUtil.nextBool's branch structure so the recorded `consumed` flag matches the real
        // stream advance (the short-circuits are load-bearing — a guaranteed/impossible prob touches NOTHING).
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

    override fun <T> choice(items: List<T>): T {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        if (items.isEmpty()) throw IllegalArgumentException("Empty items")
        // RandUtil.choice = items[rng.nextInt(size-1)] — the nextInt INDEX is the cursor-load-bearing draw,
        // the returned value is items[idx]. Record both (mirrors the PHP recorder's choiceIndex + result).
        val idx = inner.nextInt((items.size - 1).toLong()).toInt()
        val chosen = items[idx]
        record("choice", linkedMapOf("items" to items.map { it.toString() }), chosen.toString(), s, b, true, choiceIndex = idx)
        return chosen
    }
}
