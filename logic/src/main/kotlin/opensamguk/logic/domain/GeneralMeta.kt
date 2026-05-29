package opensamguk.logic.domain

fun metaInt(meta: Map<String, Any?>, key: String, default: Int = 0): Int =
    (meta[key] as? Number)?.toInt() ?: default
fun metaDouble(meta: Map<String, Any?>, key: String, default: Double = 0.0): Double =
    (meta[key] as? Number)?.toDouble() ?: default
fun withMeta(meta: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    val next = LinkedHashMap(meta); for ((k, v) in pairs) next[k] = v; return next
}
