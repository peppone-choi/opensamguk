package opensamguk.gameapi.court.vassal

/** Current live account control, never a name or NPC-state-only guess. */
object VassalHumanIdentity {
    fun read(userId: String?, npcState: Int): Boolean? {
        if (npcState !in setOf(0, 1, 2, 3, 5, 6, 9)) return null
        if (userId.isNullOrBlank()) return false
        val numeric = userId.toLongOrNull() ?: return null
        if (numeric <= 0) return false
        if (numeric.toString() != userId || npcState >= 2) return null
        return true
    }
}
