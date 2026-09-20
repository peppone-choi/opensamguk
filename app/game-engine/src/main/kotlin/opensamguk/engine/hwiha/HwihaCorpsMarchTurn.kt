package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Returns whether a deployed corps owns this commander's movement stage. */
class HwihaCorpsMarchTurn(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot, private val metrics: LandMarchMetricSnapshot) {
    fun onTurn(commanderId: Int): Boolean {
        val actor = world.getGeneralById(commanderId) ?: return false
        val projection = HwihaDeploymentExecutor(world,recorder,topology,metrics).projection()
        val corps = projection?.deployed?.singleOrNull { it.commanderGeneralId == commanderId }
        if (corps == null && HwihaCorpsOrder.META_KEY !in actor.meta) return false
        val order = try {
            HwihaCorpsOrder.read(actor.meta,topology)?.also { it.requireBinding(requireNotNull(corps),commanderId) }
        } catch (_: IllegalArgumentException) { null }
        if (order == null) { log(commanderId,"출병 명령 상태를 확인할 수 없어 행군을 멈췄습니다."); return true }
        val edges = try { HwihaLandPassageState.read(world.getState().meta,topology) }
            catch (_: IllegalArgumentException) { null }
        if (edges == null) { log(commanderId,"육상 통행 상태를 확인할 수 없어 출병 행군을 멈췄습니다."); return true }
        val before = try { HwihaCorpsMarchState.read(actor.meta,topology,metrics) }
            catch (_: IllegalArgumentException) { null }
        val military = HwihaMilitaryPresenceProvider(world,topology,metrics)
        val encounters = HwihaCorpsEncounterRecorder(world, recorder, topology, metrics)
        var defenders: List<HwihaDeployedCorps>? = null
        when (val result = HwihaCorpsMarchExecutor(world,recorder,topology,metrics,1)
            .advance(order.orderId,commanderId,order.destination,edges) { node ->
                val entry = military.entryAt(commanderId, node)
                if (entry == LandMarchEntry.ENCOUNTER) {
                    defenders = encounters.defendersAt(commanderId, node)
                    if (defenders == null) LandMarchEntry.UNAVAILABLE else entry
                } else entry
            }) {
            CorpsMarchExecution.AlreadyProcessed -> Unit
            is CorpsMarchExecution.Rejected -> log(commanderId,when(result.reason) {
                CorpsMarchFailure.BATTLE_PENDING -> "조우 처리가 끝나지 않아 출병 행군을 재개할 수 없습니다."
                CorpsMarchFailure.NO_ROUTE -> "출병 목적지까지 통행 가능한 육상 경로가 없습니다."
                else -> "출병 상태를 확인할 수 없어 이동하지 않았습니다."
            })
            is CorpsMarchExecution.Applied -> {
                if (result.state.checkpoint.stop == LandMarchStop.ENCOUNTER) {
                    encounters.record(requireNotNull(corps), requireNotNull(defenders), result.state.checkpoint)
                }
                if (before?.checkpoint?.stop == LandMarchStop.ARRIVED && result.state.checkpoint.stop == LandMarchStop.ARRIVED) return true
                log(commanderId,when(result.state.checkpoint.stop) {
                    LandMarchStop.ARRIVED -> "출병 목적지에 도착했습니다."
                    LandMarchStop.BUDGET_EXHAUSTED -> "부대를 거느리고 목적지로 행군하고 있습니다."
                    LandMarchStop.EDGE_BLOCKED -> "통행로가 닫혀 출병 행군을 멈췄습니다."
                    LandMarchStop.ENCOUNTER_UNAVAILABLE -> "진입할 지역의 군사·반응 상태를 확인할 수 없어 출병 행군을 멈췄습니다."
                    LandMarchStop.ENCOUNTER -> "군단이 조우해 출병 행군을 멈췄습니다."
                })
            }
        }
        return true
    }
    private fun log(id:Int,text:String) = world.pushLog(LogEntryDraft(scope="general",category="action",text=text,
        generalId=id,nationId=world.getGeneralById(id)?.nationId))
}
