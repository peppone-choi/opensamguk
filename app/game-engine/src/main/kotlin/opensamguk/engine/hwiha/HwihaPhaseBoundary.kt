package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.world.HanProvinceCellIndex
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/**
 * HWIHA 순 경계(세계 처리, 재설계 spec §5.2). [TurnRunService] 가 순마다(월 경계 포함) 세계 날짜를 새 순으로
 * 옮긴 직후 한 번 부른다. 월 경계 전용 단계(징세·녹봉·월단평)는 호출부가 이 뒤에 잇는다.
 *
 * 현재 단계: 2. 포위(성 안 군량·사기·항복).
 */
class HwihaPhaseBoundary(
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
) {
    fun run(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        HwihaSiegeService(world, recorder, topology, metrics, cells).settleBoundary()
    }
}
