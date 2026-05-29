package opensamguk.common.rng

fun jsKeyOrder(keys: Collection<String>): List<String> {
    val intKeys = ArrayList<String>(); val strKeys = ArrayList<String>()
    for (k in keys) if (isArrayIndex(k)) intKeys.add(k) else strKeys.add(k)
    intKeys.sortBy { it.toLong() }
    return intKeys + strKeys
}
private fun isArrayIndex(k: String): Boolean {
    if (k.isEmpty()) return false
    if (k == "0") return true
    if (k[0] == '0') return false
    if (!k.all { it in '0'..'9' }) return false
    val v = k.toLongOrNull() ?: return false
    return v in 0 until 4294967295L
}
