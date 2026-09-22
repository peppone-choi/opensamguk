package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Production personal-turn movement: both passage and reactions come from the current saved world. */
class HwihaAssignmentMarchTurn(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
) {
    fun onTurn(generalId: Int, reserved: ReservedTurn, outcome: HwihaTurnOutcome? = null) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        // A future field action must not run alongside automatic personal movement.
        val startsDeployment = reserved.actionCode == HwihaDeployInput.INPUT_ID && outcome is HwihaTurnOutcome.Applied
        if (!HwihaPersonalTurn.hasNoInput(reserved) && reserved.actionCode != HwihaEnlistmentHandler.INPUT_ID && !startsDeployment) return
        if (HwihaCorpsMarchTurn(world,recorder,topology,metrics,cells).onTurn(generalId)) return
        val actor = world.getGeneralById(generalId) ?: return
        if (HwihaCountyAssignment.META_KEY !in actor.meta) return
        val edges = try { HwihaLandPassageState.read(world.getState().meta, topology) }
            catch (_: IllegalArgumentException) { null }
        if (edges == null) {
            log(generalId, "육상 통행 상태를 확인할 수 없어 부임 행군을 멈췄습니다.")
            return
        }
        val previous = try { HwihaMarchState.read(actor.meta, topology, metrics) }
            catch (_: IllegalArgumentException) { null }
        val military = HwihaMilitaryPresenceProvider(world, topology, metrics)
        // One is the minimum positive passage threshold, not one soldier or one unit card.
        when (val result = HwihaAssignmentMarchExecutor(world, recorder, topology, metrics, requiredCapacity = 1)
            .advance(generalId, edges) { node -> military.entryAt(generalId, node) }) {
            AssignmentMarchExecution.NoAssignment, AssignmentMarchExecution.AlreadyProcessed -> Unit
            is AssignmentMarchExecution.Rejected -> if (result.reason != AssignmentMarchFailure.CORPS_DEPLOYED) {
                log(generalId, when (result.reason) {
                    AssignmentMarchFailure.INVALID_ASSIGNMENT -> "발령이 더 이상 유효하지 않아 부임 행군을 멈췄습니다."
                    AssignmentMarchFailure.BATTLE_PENDING -> "조우 처리가 끝나지 않아 부임 행군을 재개할 수 없습니다."
                    AssignmentMarchFailure.NO_ROUTE -> "발령지까지 통행 가능한 육상 경로가 없습니다."
                    else -> "부임 행군 상태를 확인할 수 없어 이동하지 않았습니다."
                })
            }
            is AssignmentMarchExecution.Applied -> {
                if (previous?.assignment == result.state.assignment && previous.stop == LandMarchStop.ARRIVED &&
                    result.state.stop == LandMarchStop.ARRIVED) return
                log(generalId, when (result.state.stop) {
                    LandMarchStop.ARRIVED -> "발령지에 도착했습니다."
                    LandMarchStop.BUDGET_EXHAUSTED -> "발령지로 행군하고 있습니다."
                    LandMarchStop.EDGE_BLOCKED -> "통행로가 닫혀 부임 행군을 멈췄습니다."
                    LandMarchStop.ENCOUNTER_UNAVAILABLE -> "진입할 지역의 군사·반응 상태를 확인할 수 없어 부임 행군을 멈췄습니다."
                    LandMarchStop.ENCOUNTER -> "조우가 발생해 부임 행군을 멈췄습니다."
                })
            }
        }
    }

    private fun log(generalId: Int, text: String) {
        world.pushLog(LogEntryDraft(scope = "general", category = "action", text = text,
            generalId = generalId, nationId = world.getGeneralById(generalId)?.nationId))
    }
}
