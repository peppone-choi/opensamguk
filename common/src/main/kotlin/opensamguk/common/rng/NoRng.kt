package opensamguk.common.rng

/**
 * Thrown when a [NoRng]-backed [RandUtil] is asked to draw. Faithful to PHP
 * `sammo\MustNotBeReachedException` (thrown by `NoRNG`'s draw methods, `src/sammo/NoRNG.php`).
 */
class MustNotBeReachedException(message: String = "NoRng: a draw was attempted on a no-RNG path") :
    IllegalStateException(message)

/**
 * A [LiteHashDrbg] whose every draw entry point THROWS — the faithful port of PHP `sammo\NoRNG`
 * (`src/sammo/NoRNG.php`): "내부에 랜덤 값을 호출하지 않을 것이 확실할 때 RNG 대용으로 사용 — 어떤
 * 함수를 호출하든 에러 발생" (used when we are certain no randomness will be drawn; any draw is an error).
 *
 * Used to thread a [RandUtil] through the zero-draw P6 paths (ALL diplomacy/scout accept commands +
 * `DiplomaticMessage.agreeMessage`) so the **absence** of a draw is itself test-enforced: if a leaf
 * accidentally draws, the throw fails the test loudly (research §8g — "parity = absence").
 *
 * Mirrors `NoRNG::rngInstance()` returning a memoized `new RandUtil(new NoRNG())`. The superclass
 * `init` runs ONE `genNextBlock()` (a SHA-512 over the empty seed) to satisfy the buffer invariant;
 * that is construction-time work, NOT a draw, so it never throws.
 */
class NoRng : LiteHashDrbg(ByteArray(0)) {
    override fun nextBytes(bytes: Int, baseBytes: Int?): ByteArray = throw MustNotBeReachedException()
    override fun nextBits(bits: Int, baseBytes: Int?): ByteArray = throw MustNotBeReachedException()
    override fun nextInt(max: Long?): Long = throw MustNotBeReachedException()
    override fun nextFloat1(): Double = throw MustNotBeReachedException()

    companion object {
        /** Memoized `RandUtil(NoRng())` — mirrors PHP `NoRNG::rngInstance()`. */
        private val instance: RandUtil by lazy { RandUtil(NoRng()) }

        fun rngInstance(): RandUtil = instance
    }
}
