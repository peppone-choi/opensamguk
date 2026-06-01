package opensamguk.logic.auction

import opensamguk.common.rng.RandUtil

/**
 * 난독화 이름 풀 — 유니크 아이템 경매에서 주최자(시스템)의 정체를 숨기기 위한 이름 관리.
 *
 * PHP `Auction::genObfuscatedName()`의 Kotlin 포팅.
 *
 * 특성:
 * - **1회 셔플**: [RandUtil.shuffle]로 한 번 섞고 재사용
 * - **영속**: KV 스토리지(`game_env.obfuscatedNamePool`)에 저장되어 서버 재시작/flush 후에도 생존
 * - **Pool 소진 시**: [initializePool]로 새 풀 생성 (새로운 시드로)
 *
 * 사용 예시:
 * ```kotlin
 * val pool = ObfuscatedNamePool(rng)
 * val nameList = pool.initializePool(candidateNames)  // 풀 생성
 * val nextName = pool.consumeNext(nameList.toMutableList())  // 이름 소비
 * ```
 *
 * @param rng 난수 생성기 — [RandUtil.shuffle]에 사용
 */
class ObfuscatedNamePool(private val rng: RandUtil) {

    companion object {
        /** KV 스토리지에서 사용하는 키 */
        const val KV_KEY = "obfuscatedNamePool"
    }

    /**
     * 이름 풀을 1회 셔플하여 생성한다.
     *
     * @param names 난독화 후보 이름 목록 (예: "남성_001", "남성_002", ...)
     * @return 셔플된 이름 목록 (영속 저장 대상)
     */
    fun initializePool(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        return rng.shuffle(names)
    }

    /**
     * 풀에서 다음 이름을 소비한다.
     *
     * @param pool 소비할 풀 (mutable — 소비된 이름은 제거됨)
     * @return 소비된 이름, 풀이 비었으면 null
     */
    fun consumeNext(pool: MutableList<String>): String? {
        if (pool.isEmpty()) return null
        return pool.removeFirst()
    }

    /**
     * 풀이 소진되었는지 확인한다.
     *
     * @param pool 확인할 풀
     * @return 풀이 비어있으면 true
     */
    fun isExhausted(pool: List<String>): Boolean = pool.isEmpty()
}
