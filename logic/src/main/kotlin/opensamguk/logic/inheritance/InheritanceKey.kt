package opensamguk.logic.inheritance

/**
 * The 11 inheritance point keys, faithful port of PHP Enums/InheritanceKey.php.
 *
 * Each key has:
 * - [storeType]: true = 직접 저장 (directly accumulated during season play),
 *              false = derived 계산 (computed at merge time from rank_data / general state)
 * - [coefficient]: the multiplier applied to the raw value when computing the final point contribution
 *
 * PHP enum order is preserved (the FOLDED_KEYS order in MergeInheritPointRank depends on it).
 */
enum class InheritanceKey(
    /** true = 직접 저장 (accumulated into storage during play); false = derived (computed at merge) */
    val storeType: Boolean,
    /** The coefficient multiplied with the raw value to produce the final point contribution */
    val coefficient: Double,
) {
    // === storeType = true (직접 저장) ===
    /** 기존 보유 포인트 (소비 가능 잔액). 이월된 포인트. */
    PREVIOUS(true, 1.0),
    /** 실행 턴 수 (턴 실행 시 +1) */
    LIVED_MONTH(true, 1.0),
    /** 최대 내정 대성공 횟수 */
    MAX_DOMESTIC_CRITICAL(true, 1.0),
    /** 활동 액션 (출병/거병/건국 등). 계수 = 3 */
    ACTIVE_ACTION(true, 3.0),
    /** 통일 이벤트 포인트 (+250 건국 / +2000 통일) */
    UNIFIER(true, 1.0),
    /** 토너먼트 포인트 */
    TOURNAMENT(true, 1.0),

    // === storeType = false (derived 계산) ===
    /** 전투: rank_data.warnum * 5 */
    COMBAT(false, 5.0),
    /** 계략: rank_data.firenum * 20 */
    SABOTAGE(false, 20.0),
    /** 숙련도: arm-type dex 합 * 0.001 (overflow reduction) */
    DEX(false, 0.001),
    /** 내기: betwin * 10 * (betwingold / betgold)^2 */
    BETTING(false, 1.0),
    /** 최대 소속: max(general.belong, aux.max_belong) * 10 */
    MAX_BELONG(false, 10.0);

    companion object {
        /** All derived keys (storeType = false) — computed at merge time */
        val DERIVED_KEYS = entries.filter { !it.storeType }

        /** All direct keys (storeType = true) — accumulated during play */
        val DIRECT_KEYS = entries.filter { it.storeType }

        /** The snake_case name used as the storage key / DB column reference */
        fun InheritanceKey.keyName(): String = name.lowercase()
    }
}
