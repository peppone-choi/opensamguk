package opensamguk.common.wire

/**
 * Faithful port of `redisCommandStream.ts` (buildTurnDaemonStreamKeys) and
 * `realtime/keys.ts` (buildGameEventChannel / normalizeProfileName).
 *
 * Command/event stream key builders do NOT trim the profile name — they
 * interpolate it verbatim (mirrors `sammo:${profileName}:turn-daemon:*`).
 * The realtime channel builder trims and defaults empty to `"unknown"`.
 */
data class TurnDaemonStreamKeys(val commandStream: String, val eventStream: String) {
    companion object {
        fun of(profileName: String) = TurnDaemonStreamKeys(
            "sammo:$profileName:turn-daemon:commands",
            "sammo:$profileName:turn-daemon:events",
        )
    }
}

fun gameEventChannel(profileName: String): String {
    val trimmed = profileName.trim()
    val normalized = if (trimmed.isNotEmpty()) trimmed else "unknown"
    return "sammo:$normalized:realtime:events"
}
