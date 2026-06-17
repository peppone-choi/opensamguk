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

/**
 * W0-4 인테이크 결과 회신 채널 — per-requestId 결과 string 키.
 *
 * engine이 [TurnDaemonCommandResult]를 [TurnDaemonEventEnvelope]로 감싸 짧은 TTL과 함께 SET하고,
 * game-api `GET /api/command/result/{requestId}`가 같은 키를 GET해 폴링 응답한다.
 * 스트림 키와 동일하게 프로필을 verbatim 보간한다(트림 없음 — [TurnDaemonStreamKeys] 규약 미러).
 */
fun commandResultKey(profileName: String, requestId: String): String =
    "sammo:$profileName:turn-daemon:result:$requestId"
