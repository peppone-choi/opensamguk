package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld

/**
 * S3 전쟁 결과 경계([WarOutcomeListener]) → 기록 스트림의 월단평 사건([RenownEventRecorder]).
 *
 * - 조우 정산 뒤 한 번: 이긴 쪽 전공(조우 승리), 진 쪽 패전(조우 패배).
 * - 縣 소유가 넘어간 직후 한 번: 점령한 장수 전공(縣 점령), 옛 관할 장수 패전(縣 상실), 두 세력 공개 기록.
 *
 * 같은 달 같은 종류는 한 건이다(기록 스트림 규칙). 쓰기는 호출한 턴의 recorder 에 실린다.
 *
 * 기록기의 전제(양쪽 명단이 겹치지 않음, 점령 전후 세력이 다름)가 깨진 호출은 조용히 거른다 — 조우 정산·함락
 * 경계에서 던지면 턴 루프가 영구히 멈춘다.
 */
class WarOutcomeRenownListener(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) : WarOutcomeListener {
    override fun onEncounterResolved(winnerIds: List<Int>, loserIds: List<Int>) {
        val winners = winnerIds.toSet()
        val losers = loserIds.toSet() - winners
        if (winners.isEmpty() && losers.isEmpty()) return
        RenownEventRecorder(world, recorder).onEncounterResolved(winners, losers)
    }

    override fun onCountyCaptured(countyId: Int, previousNationId: Int, captorNationId: Int, capturerIds: List<Int>) {
        if (previousNationId == captorNationId) return
        RenownEventRecorder(world, recorder).onCountyCaptured(countyId, previousNationId, captorNationId, capturerIds.distinct())
    }
}
