package opensamguk.logic.auction

import opensamguk.common.rng.RandUtil

/**
 * 난독화 이름 풀 — 유니크 아이템 경매에서 주최자(시스템)의 정체를 숨기기 위한 이름 관리.
 *
 * PHP `Auction::genObfuscatedName()`의 Kotlin 포팅.
 *
 * 특성:
 * - **1회 셔플**: [RandUtil.shuffle]로 한 번 섞고 재사용
 * - **영속**: [kvStorage]에 저장되어 서버 재시작/flush 후에도 생존
 * - **Pool 소진 시**: [initializePool]로 새 풀 생성 (새로운 시드로)
 *
 * 사용 예시:
 * ```kotlin
 * val pool = ObfuscatedNamePool(rng, kvStorage)
 * val nextName = pool.consumeNext()  // 남은 풀에서 이름 소비 (자동 영속)
 * if (nextName == null) {
 *     pool.initializePool(candidateNames)  // 풀 재생성
 *     pool.consumeNext()
 * }
 * ```
 *
 * @param rng 난수 생성기 — [RandUtil.shuffle]에 사용
 * @param kvStorage 영속 저장소 — `game_env` KV 스토리지 (키: [KV_KEY])
 */
class ObfuscatedNamePool(
    private val rng: RandUtil,
    private val kvStorage: MutableMap<String, Any> = mutableMapOf(),
) {

    companion object {
        /** KV 스토리지에서 사용하는 키 */
        const val KV_KEY = "obfuscatedNamePool"
    }

    /** 현재 메모리상의 풀 — [initializePool] 또는 [loadPool]로 채워진다. */
    private var _pool: MutableList<String> = mutableListOf()

    /** 현재 풀의 읽기 전용 뷰 */
    val pool: List<String> get() = _pool.toList()

    /**
     * 이름 풀을 1회 셔플하여 생성하고 [kvStorage]에 영속한다.
     *
     * PHP parity: hiddenSeed 기반으로 한 번 섞고, `game_env`에 저장.
     *
     * @param names 난독화 후보 이름 목록 (예: "남성_001", "남성_002", ...)
     * @return 셔플된 이름 목록
     */
    fun initializePool(names: List<String>): List<String> {
        if (names.isEmpty()) {
            _pool.clear()
            kvStorage[KV_KEY] = emptyList<String>()
            return emptyList()
        }
        val shuffled = rng.shuffle(names).toMutableList()
        _pool = shuffled
        kvStorage[KV_KEY] = shuffled.toList() // 영속
        return shuffled.toList()
    }

    /**
     * [kvStorage]에서 풀을 로드한다. 서버 재시작/flush 후 호출.
     *
     * @return 로드된 풀, 없으면 emptyList
     */
    fun loadPool(): List<String> {
        @Suppress("UNCHECKED_CAST")
        val saved = kvStorage[KV_KEY] as? List<String>
        if (saved != null) {
            _pool = saved.toMutableList()
        }
        return _pool.toList()
    }

    /**
     * 풀에서 다음 이름을 소비하고 [kvStorage]에 영속한다.
     *
     * @return 소비된 이름, 풀이 비었으면 null
     */
    fun consumeNext(): String? {
        if (_pool.isEmpty()) return null
        val consumed = _pool.removeFirst()
        kvStorage[KV_KEY] = _pool.toList() // 영속
        return consumed
    }

    /**
     * 풀이 소진되었는지 확인한다.
     */
    fun isExhausted(): Boolean = _pool.isEmpty()

    /**
     * 풀에 남아있는 이름 개수.
     */
    fun remainingCount(): Int = _pool.size
}
