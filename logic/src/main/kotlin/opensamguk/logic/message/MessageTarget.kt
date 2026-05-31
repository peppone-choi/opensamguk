package opensamguk.logic.message

/**
 * Faithful port of PHP `sammo\Target` + `sammo\MessageTarget`.
 *
 * [Target] is the minimal `{id, nation_id}` pair; [MessageTarget] extends it with the display fields
 * `{name, nation, color, icon}`. The `toArray`/`toArrayLight` LinkedHashMap key order is load-bearing
 * (the message jsonb is byte-compared to the PHP golden): `id, name, nation_id, nation, color, icon`.
 */
data class Target(val generalId: Int, val nationId: Int) {
    /** PHP `Target::toArray()` — `{id, nation_id}` insertion order. */
    fun toArray(): Map<String, Any?> = linkedMapOf("id" to generalId, "nation_id" to nationId)

    companion object {
        fun buildFromArray(arr: Map<String, Any?>): Target =
            Target(generalId = (arr["id"] as Number).toInt(), nationId = (arr["nation_id"] as? Number)?.toInt() ?: 0)
    }
}

/**
 * The display-rich message endpoint (PHP `MessageTarget`). The DB-bound factories `buildQuick`
 * (queries `general`) and `buildFromGeneralObj` (needs `getNationStaticInfo` + image URL) are
 * P7/intake-side (they touch DB + image config) — out of the pure-logic VO scope; this models the
 * pure factories the daemon resolve path uses: [buildFromArray] (with the 재야-fallback) and
 * [buildSystemTarget].
 *
 * QUARANTINE NOTE: PHP defaults an empty `$icon` to `ServConfig::getSharedIconPath().'/default.jpg'`
 * (a config-dependent string). The logic VO keeps the empty icon verbatim; the default-icon string is
 * a P7/config concern (resolved request-side), not a daemon-golden parity target.
 */
data class MessageTarget(
    val generalId: Int,
    val generalName: String,
    val nationId: Int,
    val nationName: String,
    val color: String,
    val icon: String = "",
) {
    /** PHP `MessageTarget::toArray()` — `{id, name, nation_id, nation, color, icon}` insertion order. */
    fun toArray(): Map<String, Any?> = linkedMapOf(
        "id" to generalId,
        "name" to generalName,
        "nation_id" to nationId,
        "nation" to nationName,
        "color" to color,
        "icon" to icon,
    )

    /** PHP `MessageTarget::toArrayLight()` — `{name, nation, color, icon}` (drops id + nation_id). */
    fun toArrayLight(): Map<String, Any?> = linkedMapOf(
        "name" to generalName,
        "nation" to nationName,
        "color" to color,
        "icon" to icon,
    )

    fun toTarget(): Target = Target(generalId, nationId)

    companion object {
        /**
         * PHP `MessageTarget::buildFromArray` — the 재야-fallback: when `nation_id` is falsy (0/absent)
         * the nation becomes `재야` / color `#000000` / nation_id 0 (an unaffiliated general).
         */
        fun buildFromArray(arr: Map<String, Any?>): MessageTarget {
            require(arr.isNotEmpty()) { "MessageTarget.buildFromArray: empty array" }
            val rawNationId = (arr["nation_id"] as? Number)?.toInt() ?: 0
            val nationId: Int
            val nationName: String
            val color: String
            if (rawNationId == 0) {
                nationId = 0
                nationName = "재야"
                color = "#000000"
            } else {
                nationId = rawNationId
                nationName = arr["nation"] as? String ?: "재야"
                color = arr["color"] as? String ?: "#000000"
            }
            return MessageTarget(
                generalId = (arr["id"] as Number).toInt(),
                generalName = arr["name"] as? String ?: "",
                nationId = nationId,
                nationName = nationName,
                color = color,
                icon = arr["icon"] as? String ?: "",
            )
        }

        /** PHP `MessageTarget::buildSystemTarget` — `(0, '', 0, 'System', '#000000')`. */
        fun buildSystemTarget(): MessageTarget =
            MessageTarget(generalId = 0, generalName = "", nationId = 0, nationName = "System", color = "#000000")
    }
}
