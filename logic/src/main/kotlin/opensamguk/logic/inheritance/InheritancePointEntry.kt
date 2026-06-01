package opensamguk.logic.inheritance

/**
 * One resolved inheritance point entry — the key, its raw value, and the coefficient-applied computed value.
 *
 * Used by the merge path to record per-key snapshots and by the apply path to sum total points.
 */
data class InheritancePointEntry(
    /** The inheritance key (e.g. COMBAT, SABOTAGE) */
    val key: InheritanceKey,
    /** The raw unmultiplied value (e.g. warnum for combat, firenum for sabotage) */
    val rawValue: Double,
    /** coefficient 적용 후 값 (e.g. warnum * 5.0 for combat) */
    val computedValue: Double,
)
