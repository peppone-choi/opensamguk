package opensamguk.logic.input

/**
 * Optional vision inputs owned by the domestic-inputs stream (배치 정찰 · 공사 망루봉화). This file fixes the
 * storage keys and shapes the vision reader expects; until that stream writes them, both are simply absent
 * and contribute no vision. Contract: docs/superpowers/specs/2026-09-23-hwiha-vision-contract.md §3.
 *
 * A malformed record never turns into vision (it would be invented sight) and never fails the whole projection
 * (one bad row would blind everyone); it is counted in [SourceRead.invalid] so the API can surface it.
 */
data class SourceRead<T>(val value: T, val invalid: Int) {
    init { require(invalid >= 0) }
}

/** A scout placement that has arrived. [provinceId] is a pinned land province id. */
data class HwihaScoutPost(val retainerId: Int, val provinceId: String) {
    init { require(retainerId > 0 && provinceId.isNotBlank() && provinceId.length <= 128) }
}

interface HwihaVisionSourceReader {
    /** Arrived scout posts stored on the owning general's meta. */
    fun scoutPosts(ownerMeta: Map<String, Any?>): SourceRead<List<HwihaScoutPost>>

    /** Whether a county seat carries a completed watchtower/beacon work. */
    fun hasCompletedWatchtower(cityMeta: Map<String, Any?>): SourceRead<Boolean>
}

/**
 * Default reader over general/city meta.
 *
 * - General meta `hwihaScoutPosts`: `{"version":1,"posts":[{"retainerId":int,"provinceId":str,"status":"ACTIVE"|…}]}`.
 *   Only `ACTIVE` (the card physically arrived) gives vision; any other status is ignored, not invalid.
 * - City meta `hwihaCountyWorks`: `{"version":1,"works":[{"kind":str,"status":str}]}`. Vision reads only
 *   `kind == WATCHTOWER_BEACON && status == COMPLETE`; other kinds/statuses belong to the works stream.
 */
object HwihaMetaVisionSourceReader : HwihaVisionSourceReader {
    const val SCOUT_POSTS_KEY = "hwihaScoutPosts"
    const val COUNTY_WORKS_KEY = "hwihaCountyWorks"
    const val ACTIVE = "ACTIVE"
    const val WATCHTOWER_BEACON = "WATCHTOWER_BEACON"
    const val COMPLETE = "COMPLETE"

    override fun scoutPosts(ownerMeta: Map<String, Any?>): SourceRead<List<HwihaScoutPost>> {
        if (SCOUT_POSTS_KEY !in ownerMeta) return SourceRead(emptyList(), 0)
        val root = ownerMeta[SCOUT_POSTS_KEY] as? Map<*, *> ?: return SourceRead(emptyList(), 1)
        if (root.keys != setOf("version", "posts") || root["version"] != 1) return SourceRead(emptyList(), 1)
        val rows = root["posts"] as? List<*> ?: return SourceRead(emptyList(), 1)
        var invalid = 0
        val posts = rows.mapNotNull { row ->
            val map = row as? Map<*, *>
            val retainer = map?.get("retainerId") as? Int
            val province = map?.get("provinceId") as? String
            val status = map?.get("status") as? String
            if (map == null || map.keys != setOf("retainerId", "provinceId", "status") || retainer == null || retainer <= 0 ||
                province.isNullOrBlank() || province.length > 128 || status.isNullOrBlank()) {
                invalid++; return@mapNotNull null
            }
            if (status == ACTIVE) HwihaScoutPost(retainer, province) else null
        }
        return SourceRead(posts.distinct().sortedWith(compareBy({ it.retainerId }, { it.provinceId })), invalid)
    }

    override fun hasCompletedWatchtower(cityMeta: Map<String, Any?>): SourceRead<Boolean> {
        if (COUNTY_WORKS_KEY !in cityMeta) return SourceRead(false, 0)
        val root = cityMeta[COUNTY_WORKS_KEY] as? Map<*, *> ?: return SourceRead(false, 1)
        if (root["version"] != 1) return SourceRead(false, 1)
        val rows = root["works"] as? List<*> ?: return SourceRead(false, 1)
        var invalid = 0
        var complete = false
        rows.forEach { row ->
            val map = row as? Map<*, *>
            val kind = map?.get("kind") as? String
            val status = map?.get("status") as? String
            if (kind.isNullOrBlank() || status.isNullOrBlank()) { invalid++; return@forEach }
            if (kind == WATCHTOWER_BEACON && status == COMPLETE) complete = true
        }
        return SourceRead(complete, invalid)
    }
}
