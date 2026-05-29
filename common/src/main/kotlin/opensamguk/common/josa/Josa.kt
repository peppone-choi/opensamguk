package opensamguk.common.josa

internal object JosaTables {
    val DEFAULT_POSTPOSITION: Map<String, String> = linkedMapOf(
        "은" to "는", "이" to "가", "과" to "와", "이나" to "나",
        "을" to "를", "으로" to "로", "이라" to "라", "이랑" to "랑",
    )
    val MAP_POSTPOSITION: Map<String, String> = buildMap {
        for ((key, value) in DEFAULT_POSTPOSITION) { put(key, key); put(value, key); put("($key)$value", key) }
    }
}
