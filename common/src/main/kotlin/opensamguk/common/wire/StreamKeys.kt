package opensamguk.common.wire

import opensamguk.common.world.WorldId

/**
 * Faithful port of `redisCommandStream.ts` (buildTurnDaemonStreamKeys) and
 * `realtime/keys.ts` (buildGameEventChannel / normalizeProfileName), extended for
 * OPENSAM-127 so every Redis identity includes the process [WorldId].
 *
 * Command/event stream key builders do NOT trim the profile name — they
 * interpolate it verbatim. The realtime channel builder trims and defaults empty to `"unknown"`.
 * World segment is always `w{positiveId}` from [WorldId].
 */
data class TurnDaemonStreamKeys(val commandStream: String, val eventStream: String) {
    companion object {
        fun of(profileName: String, worldId: WorldId) = TurnDaemonStreamKeys(
            "sammo:$profileName:w${worldId.value}:turn-daemon:commands",
            "sammo:$profileName:w${worldId.value}:turn-daemon:events",
        )
    }
}

fun gameEventChannel(profileName: String, worldId: WorldId): String {
    val trimmed = profileName.trim()
    val normalized = if (trimmed.isNotEmpty()) trimmed else "unknown"
    return "sammo:$normalized:w${worldId.value}:realtime:events"
}

/**
 * W0-4 인테이크 결과 회신 채널 — per-requestId 결과 string 키.
 * World-scoped so two process worlds never share result keys for the same requestId.
 */
fun commandResultKey(profileName: String, worldId: WorldId, requestId: String): String =
    "sammo:$profileName:w${worldId.value}:turn-daemon:result:$requestId"
