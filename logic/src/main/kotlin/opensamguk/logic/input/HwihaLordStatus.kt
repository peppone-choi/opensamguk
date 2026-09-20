package opensamguk.logic.input

/** Event-owned status in general.meta. Offices and county ownership are not evidence of lordship. */
object HwihaLordStatus {
    const val META_KEY = "hwihaLord"

    /** A newly created unmarked general is not a lord; malformed persisted state is an error. */
    fun read(meta: Map<String, Any?>): Boolean {
        if (META_KEY !in meta) return false
        return requireNotNull(meta[META_KEY] as? Boolean) { "hwihaLord must be boolean" }
    }

    /** Caller records the returned metadata through the existing general ChangeRecorder path. */
    fun afterEnlistment(meta: Map<String, Any?>): Map<String, Any?> {
        read(meta)
        return LinkedHashMap(meta).apply { put(META_KEY, false) }
    }
}
