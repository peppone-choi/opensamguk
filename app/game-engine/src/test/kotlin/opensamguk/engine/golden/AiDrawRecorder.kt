package opensamguk.engine.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil

/**
 * P5 GT3 — the engine-side draw-recording harness, SYMMETRIC to the PHP
 * `tools/php-golden/RandUtilDrawRecorder.php` AND to `:logic`'s `BattleDrawRecorder` (the P4 G1 recorder).
 *
 * A faithful, DRAW-NEUTRAL decorator over the game's [RandUtil]. The live AI's `chooseNationTurn` /
 * `chooseGeneralTurn` thread their SOLE `"GeneralAI"` rng BY REFERENCE into every `do<한글>` body; to observe
 * every draw the recorder MUST be an `is RandUtil`. We extend [RandUtil] and override each `open` draw method
 * to (1) snapshot the underlying [LiteHashDrbg] cursor (stateIdx/bufferIdx) BEFORE the draw, (2) consume
 * EXACTLY ONE logical draw off the same inner DRBG (byte-identical to bare [RandUtil] — we replicate the
 * parent's body against [inner] rather than call `super`, so an `open` `nextFloat1` is never double-dispatched
 * and no draw is double-logged), (3) log `{seq, method, args, result, consumed, stateIdxBefore, bufferIdxBefore}`.
 *
 * The non-`open` `choiceUsingWeight`/`choiceUsingWeightPair`/`choiceMap`/`choiceSet` helpers internally call the
 * `open` `nextFloat1()`/`choice(...)` — so they record through virtual dispatch on this subclass automatically
 * (the live AI's weighted picks surface as their underlying `nextFloat1`/`choice` draws, exactly as the PHP
 * recorder reflects them).
 *
 * **Cursor fingerprint.** [LiteHashDrbg.peekStateIdx] (the SHA-512 block counter) + [LiteHashDrbg.peekBufferIdx]
 * (byte offset within the 64-byte block) are the byte-exact stream position — the SAME stateIdx/bufferIdx the
 * PHP recorder reflects, so the gate asserts value-for-value AND cursor-for-cursor at the first divergent draw.
 *
 * **Short-circuit fidelity (load-bearing).** `nextBool(prob>=1)` ⇒ true / `nextBool(prob<=0)` ⇒ false consume
 * NO draw; recorded with `consumed=false`, cursor unchanged — exactly the PHP recorder's behavior.
 */
class AiDrawRecorder(private val inner: LiteHashDrbg) : RandUtil(inner) {

    data class Draw(
        val seq: Int,
        val method: String,
        val args: Map<String, Any?>,
        val result: Any?,
        val consumed: Boolean,
        val stateIdxBefore: Long,
        val bufferIdxBefore: Int,
        val choiceIndex: Int? = null,
    )

    private val stream = mutableListOf<Draw>()
    private var seq = 0

    fun drawStream(): List<Draw> = stream
    fun drawCount(): Int = stream.size
    fun stateIdx(): Long = inner.peekStateIdx()

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

    override fun nextFloat1(): Double {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = inner.nextFloat1()
        record("nextFloat1", emptyMap(), r, s, b, true)
        return r
    }

    override fun nextRange(min: Double, max: Double): Double {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = inner.nextFloat1() * (max - min) + min
        record("nextRange", linkedMapOf("min" to min, "max" to max), r, s, b, true)
        return r
    }

    override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = inner.nextInt((maxInclusive - minInclusive).toLong()).toInt() + minInclusive
        record("nextRangeInt", linkedMapOf("min" to minInclusive, "max" to maxInclusive), r, s, b, true)
        return r
    }

    override fun nextInt(minInclusive: Int, maxExclusive: Int): Int {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
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
        val idx = inner.nextInt((items.size - 1).toLong()).toInt()
        val chosen = items[idx]
        record("choice", linkedMapOf("items" to items.map { it.toString() }), chosen.toString(), s, b, true, choiceIndex = idx)
        return chosen
    }

    /**
     * BYTE-SYMMETRIC to the PHP `RandUtilDrawRecorder`, which overrides EXACTLY `choice` / `choiceUsingWeight` /
     * `shuffle` (+ the primitives) — and NOTHING else. So we record a `choiceUsingWeight` WRAPPER entry (its
     * inner `nextFloat1` records FIRST via virtual dispatch, then this wrapper rides on top with the cursor
     * snapshotted BEFORE the inner draw), reproducing the GT2 crafted goldens' `…, nextFloat1(c),
     * choiceUsingWeight(c), …` interleaving exactly.
     *
     * We deliberately do NOT override `choiceUsingWeightPair` / `choiceMap` / `choiceSet` — the PHP recorder
     * does not either, so those surface ONLY as their underlying `nextFloat1`/`choice` draw (the GT1 do일반내정
     * path uses `choiceUsingWeightPair`; an extra wrapper there would inject a phantom draw the golden lacks).
     */
    override fun choiceUsingWeight(items: Map<String, Double>): String {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = super.choiceUsingWeight(items) // records the inner nextFloat1 FIRST (virtual dispatch).
        record("choiceUsingWeight", linkedMapOf("items" to items.keys.toList()), r, s, b, true)
        return r
    }

    /**
     * The Kotlin AI families use `choiceUsingWeightPair` (Int-key insertion order) where PHP uses EITHER
     * `choiceUsingWeight` (do징병/do선전포고 armType/target — golden HAS a wrapper) OR `choiceUsingWeightPair`
     * (do일반내정 — golden has NO wrapper). We record the wrapper here for symmetry/observability; the gate's
     * `diffStream`/count comparisons COLLAPSE these zero-byte wrapper entries on BOTH golden and live (their
     * before-cursor == the inner `nextFloat1` they ride on), so the wrapper-vs-pair shape never desyncs — only
     * the inner RNG draw (value + cursor) is asserted.
     */
    override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
        val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
        val r = super.choiceUsingWeightPair(items) // records the inner nextFloat1 FIRST (virtual dispatch).
        record("choiceUsingWeightPair", linkedMapOf("items" to items.map { it.first.toString() }), r.toString(), s, b, true)
        return r
    }

    /**
     * [RandUtil.shuffle] calls `rng.nextInt(...)` on the PRIVATE inner DRBG directly (NOT `this.nextInt`), so
     * we replicate its Fisher-Yates body against [inner] and record EACH per-position `nextInt` draw — keeping
     * the cursor logged (an un-logged shuffle would silently advance the stream). Not exercised by the month-1
     * 1010 window, but recorded for fidelity so any future shuffle path is observed, not lost.
     */
    override fun <T> shuffle(srcArray: List<T>): List<T> {
        val cnt = srcArray.size
        if (cnt == 0) return emptyList()
        val result = ArrayList(srcArray)
        for (srcIdx in 0 until cnt) {
            val s = inner.peekStateIdx(); val b = inner.peekBufferIdx()
            val span = (cnt - srcIdx - 1).toLong()
            val destIdx: Int
            if (span <= 0L) {
                destIdx = srcIdx
                record("shuffle", linkedMapOf("srcIdx" to srcIdx, "span" to span), destIdx, s, b, false)
            } else {
                destIdx = inner.nextInt(span).toInt() + srcIdx
                record("shuffle", linkedMapOf("srcIdx" to srcIdx, "span" to span), destIdx, s, b, true)
            }
            if (srcIdx == destIdx) continue
            val tmp = result[srcIdx]; result[srcIdx] = result[destIdx]; result[destIdx] = tmp
        }
        return result
    }
}
