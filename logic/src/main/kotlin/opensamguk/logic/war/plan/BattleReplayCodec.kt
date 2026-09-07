package opensamguk.logic.war.plan

import java.security.MessageDigest

/**
 * 리플레이 직렬화·해시(spec v4.1 §5 S5·S7). 키 정렬·리스트 순서 유지·Double 은 `toBits()` 로 정규화한 JSON 을 그대로
 * SHA-256 한다. `battle_phases_json` 은 저장 바이트가 곧 해시 입력이라 TEXT 로 보관한다.
 * 이름은 **입력**이다(`names(kind, id)`) — 결정성 테스트는 고정 이름 맵을 주입한다(M6).
 */
object BattleReplayCodec {

    fun encodePhases(draft: BattleReplayDraft, names: (kind: String, id: Int) -> String): String {
        val phases = draft.phases.map { p ->
            linkedMapOf<String, Any?>(
                "i" to p.index, "defId" to p.defId, "def" to names(p.defKind, p.defId), "defKind" to p.defKind, "contact" to p.contact,
                "deadA" to p.deadAttacker, "deadD" to p.deadDefender, "crewA" to p.crewAttacker, "hpD" to p.hpDefender,
            )
        }
        val stop = mapOf<String, Any?>("kind" to draft.stop?.code, "atPhase" to draft.stopAtPhase)
        return encode(mapOf("v" to BattlePlanRules.REPLAY_SCHEMA_VERSION, "phases" to phases, "stop" to stop))
    }

    /** `replay_hash` = 페이즈 JSON + 정산(키 정렬)의 SHA-256. */
    fun replayHash(phasesJson: String, settlement: Map<String, Any?>): String = sha256Hex(phasesJson + "|" + encode(settlement))

    /** `input_hash` = 정규화 입력의 SHA-256(부분 지문 — 게이트는 같은 메모리 입력 두 번 실행의 replay_hash 동일성). */
    fun inputHash(input: Map<String, Any?>): String = sha256Hex(encode(input))

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 키 정렬 JSON — 맵은 키 오름차순, 리스트는 순서 유지, Double 은 `toBits()`, 나머지는 그대로. */
    fun encode(value: Any?): String {
        val sb = StringBuilder(); write(sb, value); return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Double -> sb.append(value.toBits())
            is Float -> sb.append(value.toDouble().toBits())
            is Number -> sb.append(value.toString())
            is String -> writeString(sb, value)
            is Enum<*> -> writeString(sb, value.name)
            is Map<*, *> -> {
                sb.append('{')
                value.entries.map { it.key.toString() to it.value }.sortedBy { it.first }.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(','); writeString(sb, k); sb.append(':'); write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('['); value.forEachIndexed { i, v -> if (i > 0) sb.append(','); write(sb, v) }; sb.append(']')
            }
            is Array<*> -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}
