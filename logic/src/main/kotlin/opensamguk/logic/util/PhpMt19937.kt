package opensamguk.logic.util

/**
 * PHP 네이티브 `mt_rand` 계열(`MT_RAND_MT19937` 모드)의 byte-exact 포팅.
 *
 * 대회(토너먼트) fight() 경로(func_tournament.php:1004)는 sammo RandUtil/LiteHashDRBG가 아니라
 * PHP 네이티브 `rand()`/`mt_rand()`/`array_rand()`를 쓴다(★ 유일한 예외 경로):
 *  - `rand()` = `mt_rand()` 별칭 (PHP 7.1+).
 *  - `Util::randRangeInt(min,max)` = `mt_rand(min,max)` (Util.php:457-459).
 *  - `Util::choiceRandom($items)` = `$items[array_rand($items)]` (Util.php:648-651);
 *    packed 리스트의 `array_rand` 단일 키 = `php_mt_rand_range(0, count-1)` (ext/standard/array.c).
 *
 * 구현 근거 (php-src ext/standard/mt_rand.c, PHP 7.1~8.x, MT_RAND_MT19937):
 *  - `php_mt_initialize` = 표준 init_genrand (Knuth: state[i] = 1812433253*(s^(s>>30))+i).
 *  - `php_mt_reload` = 표준 MT19937 twist (`twist` 매크로 = loBit(v) 분기; MT_RAND_PHP의
 *    `twist_php`(loBit(u)) 변형이 아님).
 *  - `php_mt_rand()` = 표준 tempering의 32-bit 원값; `mt_rand()` 무인자는 `>> 1` (31-bit).
 *  - `php_mt_rand_range(min,max)` = 전체 32-bit 값 + rejection sampling(`rand_range32`):
 *    2의 거듭제곱이면 `& (umax-1)`, 아니면 `limit = UINT32_MAX - (UINT32_MAX % umax) - 1`
 *    초과분 재추첨 후 `% umax`.
 *
 * 적합성 증명: golden/tournament/fight-fixtures.json `rngConformance` (real PHP 캡처, seed
 * {1,777,12345} × {mt_rand, %100, mt_rand(1,100), mt_rand(150,300), array_rand(6)}) 벡터가
 * TournamentFightGoldenTest에서 byte-match 게이트로 검증한다.
 */
class PhpMt19937(seed: Int) {
    private val state = IntArray(N)
    private var index = N // 다음 reload 강제 (php_mt_srand는 initialize+reload — 시퀀스 동일)

    init {
        // php_mt_initialize — 표준 init_genrand. Int 연산의 자연 wrap = uint32 mod 2^32.
        state[0] = seed
        for (i in 1 until N) {
            state[i] = 1812433253 * (state[i - 1] xor (state[i - 1] ushr 30)) + i
        }
    }

    /** php_mt_reload — 표준 MT19937 nextState (in-place; i=623은 갱신된 state[0]을 쓰는 textbook 동작). */
    private fun reload() {
        for (i in 0 until N) {
            val y = (state[i] and UPPER_MASK) or (state[(i + 1) % N] and LOWER_MASK)
            var next = state[(i + M) % N] xor (y ushr 1)
            if (y and 1 == 1) next = next xor MATRIX_A
            state[i] = next
        }
        index = 0
    }

    /** php_mt_rand() — tempered 32-bit 원값 (Int 비트패턴; 음수 = 상위비트 set). */
    fun genrandInt32(): Int {
        if (index >= N) reload()
        var y = state[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and TEMPER_B)
        y = y xor ((y shl 15) and TEMPER_C)
        return y xor (y ushr 18)
    }

    /** PHP `mt_rand()` / `rand()` 무인자 — 31-bit(>>1), 항상 0 이상. */
    fun mtRand(): Int = genrandInt32() ushr 1

    /** PHP `mt_rand(min,max)` / `rand(min,max)` = php_mt_rand_range (PHP 7.1+ rejection sampling). */
    fun mtRand(min: Int, max: Int): Int {
        val umax = max.toLong() - min.toLong() // 이 코드베이스의 도달 범위는 32-bit 미만 (rand_range64 불필요)
        return (randRange32(umax) + min).toInt()
    }

    /** PHP `array_rand($arr)` (packed 리스트, 단일 키) = php_mt_rand_range(0, size-1) — Util::choiceRandom 경로. */
    fun arrayRand(size: Int): Int = mtRand(0, size - 1)

    /** rand_range32 — 전체 32-bit 값 기반 modulo-bias 제거 rejection sampling. */
    private fun randRange32(umaxIn: Long): Long {
        var result = genrandInt32().toLong() and UINT32_MAX
        if (umaxIn == UINT32_MAX) return result
        val umax = umaxIn + 1 // max 포함 범위로 증가
        if (umax and (umax - 1) == 0L) return result and (umax - 1) // 2의 거듭제곱은 bias 없음
        val limit = UINT32_MAX - (UINT32_MAX % umax) - 1
        while (result > limit) result = genrandInt32().toLong() and UINT32_MAX
        return result % umax
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val UPPER_MASK = 0x80000000.toInt()
        const val LOWER_MASK = 0x7fffffff
        const val MATRIX_A = 0x9908b0df.toInt()
        const val TEMPER_B = 0x9d2c5680.toInt()
        const val TEMPER_C = 0xefc60000.toInt()
        const val UINT32_MAX = 0xFFFFFFFFL
    }
}
