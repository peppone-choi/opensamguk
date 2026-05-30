package opensamguk.logic.domain

fun metaInt(meta: Map<String, Any?>, key: String, default: Int = 0): Int =
    (meta[key] as? Number)?.toInt() ?: default
fun metaDouble(meta: Map<String, Any?>, key: String, default: Double = 0.0): Double =
    (meta[key] as? Number)?.toDouble() ?: default
fun withMeta(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    val next = LinkedHashMap(meta); for ((k, v) in pairs) next[k] = v; return next
}

// --- P2 (Task FD0): meta-resident exp / level accessors ---
// PHP key names verified against GeneralBase.php:159-161 (the var allow-list): leadership_exp /
// strength_exp / intel_exp / explevel / dedlevel. These ride the `meta` jsonb (NO dedicated columns;
// V1 general table has no leadership_exp/strength_exp/dedlevel/intel_exp), consistent with P1.

/** `meta['leadership_exp']` — the leadership *_exp accumulator (raw float, truncated at flush). */
fun General.leadershipExp(): Double = metaDouble(meta, "leadership_exp")
/** `meta['strength_exp']` — the strength *_exp accumulator. */
fun General.strengthExp(): Double = metaDouble(meta, "strength_exp")
/** `meta['intel_exp']` — the intel *_exp accumulator (already used in P1). */
fun General.intelExp(): Double = metaDouble(meta, "intel_exp")
/** `meta['explevel']` — the experience level (checkStatChange level-up/down target). */
fun General.explevel(): Int = metaInt(meta, "explevel")
/** `meta['dedlevel']` — the dedication level (승급/강등 target). */
fun General.dedlevel(): Int = metaInt(meta, "dedlevel")

/**
 * PHP `getAuxVar(key)` — reads a nested `aux` map on `meta` (GeneralBase.php:155/165 list `aux`).
 * Returns null when `aux` or the key is absent (delete-on-null jsonb on flush).
 */
fun General.auxVar(key: String): Any? {
    @Suppress("UNCHECKED_CAST")
    val aux = meta["aux"] as? Map<String, Any?> ?: return null
    return aux[key]
}
