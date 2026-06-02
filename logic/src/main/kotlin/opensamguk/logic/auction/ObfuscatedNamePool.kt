package opensamguk.logic.auction

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed

/**
 * 난독화 이름 풀 — 유니크 아이템 경매에서 입찰자의 실제 이름을 숨기기 위한 익명 이름 생성.
 *
 * PHP `Auction::genObfuscatedName(int $id)`의 byte-faithful 포팅.
 *
 * 알고리즘 (PHP parity):
 * 1. 풀 생성 (lazy, KV `game_env.obfuscatedNamePool`에 1회 캐시):
 *    - `rng = RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed, 'obfuscatedNamePool')))`
 *    - `randGenFirstName × randGenMiddleName × randGenLastName` 3중 루프로 `"{ch0}{ch1}{ch2}"` 조합
 *    - `rng.shuffle(pool)` 1회 셔플 후 KV에 영속
 * 2. id → 이름 디코드 (`genObfuscatedName($id)`):
 *    - `dupIdx = intdiv(id, count)` · `subIdx = id % count`
 *    - `dupIdx == 0` 이면 `pool[subIdx]`, 아니면 `"{pool[subIdx]}{dupIdx}"`
 *
 * 동일 id는 항상 동일한 난독 이름을 반환한다(순차 소비가 아니라 결정적 디코드).
 */
object ObfuscatedNamePool {
    /** KV 스토리지에서 사용하는 키 (PHP `game_env.obfuscatedNamePool`). */
    const val KV_KEY = "obfuscatedNamePool"

    /** 풀 크기 = first × middle × last 조합 수. PHP `count($namePool)`. */
    val poolSize: Int
        get() = GameConst.randGenFirstName.size *
            GameConst.randGenMiddleName.size *
            GameConst.randGenLastName.size

    /**
     * 시드로부터 결정적으로 난독 이름 풀을 생성한다.
     *
     * PHP: `randGenFirstName × randGenMiddleName × randGenLastName` 순서로 조합한 뒤 `rng->shuffle`.
     * 동일 시드 → 동일 풀, 다른 시드 → 다른 셔플.
     *
     * @param seed DRBG 시드 문자열. 프로덕션에서는 `serializeSeed(hiddenSeed, KV_KEY)`를 전달한다.
     */
    fun buildPool(seed: String): List<String> {
        val pool = ArrayList<String>(poolSize)
        for (ch0 in GameConst.randGenFirstName) {
            for (ch1 in GameConst.randGenMiddleName) {
                for (ch2 in GameConst.randGenLastName) {
                    pool.add("$ch0$ch1$ch2")
                }
            }
        }
        return RandUtil(LiteHashDrbg(seed)).shuffle(pool)
    }

    /**
     * id를 난독 이름으로 디코드한다 (PHP `genObfuscatedName` 후반부).
     *
     * `dupIdx = id / size`, `subIdx = id % size`. `dupIdx == 0`이면 접미사 없음, 아니면 `dupIdx` 접미사.
     */
    fun decode(id: Int, pool: List<String>): String {
        val size = pool.size
        val dupIdx = id / size
        val subIdx = id % size
        return if (dupIdx == 0) pool[subIdx] else "${pool[subIdx]}$dupIdx"
    }

    /**
     * 프로덕션 진입점 — KV에서 풀을 lazy 로드/생성한 뒤 id를 디코드한다.
     *
     * PHP `Auction::genObfuscatedName($id)` 전체 흐름과 동일:
     * 풀이 없으면 `serializeSeed(hiddenSeed, KV_KEY)` 시드로 생성하여 KV에 캐시한다.
     *
     * @param id 입찰 장수 id
     * @param kvStorage `game_env` KV 스토리지
     * @param hiddenSeed 시나리오 hiddenSeed 문자열
     */
    fun genObfuscatedName(id: Int, kvStorage: MutableMap<String, Any>, hiddenSeed: String): String {
        @Suppress("UNCHECKED_CAST")
        val cached = kvStorage[KV_KEY] as? List<String>
        val pool = cached ?: buildPool(serializeSeed(hiddenSeed, KV_KEY)).also { kvStorage[KV_KEY] = it }
        return decode(id, pool)
    }
}
