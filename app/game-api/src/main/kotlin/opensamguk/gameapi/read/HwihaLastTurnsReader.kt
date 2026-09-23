package opensamguk.gameapi.read

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.HwihaLastTurnDto
import opensamguk.gameapi.dto.HwihaLastTurnsResponse
import opensamguk.gameapi.dto.HwihaNationSummaryEntryDto
import opensamguk.gameapi.dto.HwihaRecordEntryDto
import opensamguk.logic.input.HwihaRecordKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 「지난 순」 조회 — 개인 기록 최근 [limit]순(기본 12, 명령 목록 12순의 거울)과 세력 요약.
 *
 * - 창은 지금 세계 시각(연·월·순)을 끝으로 거꾸로 [limit]순이다. 기록이 없던 순도 빈 칸으로 싣는다.
 * - **개인 기록**은 본인 앞 줄만(`scope=GENERAL`, `general_id` = 본인) — 쓰는 쪽이 그 장수가 알아도 되는 것만
 *   그 장수 앞으로 쓴다(#343, `HwihaRecordKind`).
 * - **세력 요약**은 본인 세력의 공개 사건([HwihaRecordKind.NATION_SUMMARY_KINDS])과 세계 공개 사건
 *   ([HwihaRecordKind.WORLD_SUMMARY_KINDS])만. 세력 내부 기록(월세입)은 싣지 않는다.
 *
 * 인증은 다른 휘하 조회와 같다([ownedHwihaGeneral]·[hwihaGate]).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaLastTurnsReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val records: HwihaRecordReadRepository,
    private val objectMapper: ObjectMapper,
) {
    fun lastTurns(generalId: Int, userId: Long, limit: Int): HwihaLastTurnsResponse {
        val actor = ownedHwihaGeneral(generals, generalId, userId)
        hwihaGate(worlds, actor)?.let { return HwihaLastTurnsResponse(it) }
        val world = worlds.findProcessWorld() ?: return HwihaLastTurnsResponse("UNAVAILABLE")
        val now = try { HwihaTurnStamp(world.currentYear, world.currentMonth, world.currentPhase) }
            catch (_: IllegalArgumentException) { return HwihaLastTurnsResponse("UNAVAILABLE") }
        val size = limit.coerceIn(1, MAX_LIMIT)
        val from = HwihaTurnStamp.ofOrdinal(maxOf(0, now.ordinal - (size - 1)))

        val personal = records.personal(actor.id, from, now).groupBy { HwihaTurnStamp(it.year, it.month, it.phase) }
        val turns = (now.ordinal downTo from.ordinal).map { ordinal ->
            val turn = HwihaTurnStamp.ofOrdinal(ordinal)
            HwihaLastTurnDto(turn.year, turn.month, turn.phase, HwihaRecordKind.phaseLabel(turn.phase),
                personal[turn].orEmpty().map { HwihaRecordEntryDto(it.kind, it.text, refs(it.refsJson)) })
        }
        val summary = records.summary(actor.nationId, HwihaRecordKind.NATION_SUMMARY_KINDS,
            HwihaRecordKind.WORLD_SUMMARY_KINDS, from, now)
            .sortedWith(compareByDescending<HwihaRecordRow> { HwihaTurnStamp(it.year, it.month, it.phase).ordinal }
                .thenBy { it.id })
            .map {
                HwihaNationSummaryEntryDto(it.year, it.month, it.phase, HwihaRecordKind.phaseLabel(it.phase),
                    it.kind, it.text, refs(it.refsJson))
            }
        return HwihaLastTurnsResponse("READY", turns, summary)
    }

    /** 식별자는 곁들임이다 — 읽을 수 없으면 빈 묶음으로 두고 기록 문장은 그대로 낸다. */
    private fun refs(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try { objectMapper.readValue(json, REFS_TYPE) ?: emptyMap() } catch (_: Exception) { emptyMap() }
    }

    companion object {
        /** 한 번에 볼 수 있는 가장 긴 창 — 한 해(36순). */
        const val MAX_LIMIT = 36
        const val DEFAULT_LIMIT = 12
        private val REFS_TYPE = object : TypeReference<LinkedHashMap<String, Any?>>() {}
    }
}
