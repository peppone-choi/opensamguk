package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** Returns whether a deployed corps owns this commander's movement stage. */
class CorpsMarchTurn(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot, private val metrics: LandMarchMetricSnapshot,
    private val cells: HanProvinceCellIndex,
    private val reactions: MarchReactionPolicy = MarchReactionPolicy.NON_BLOCKING) {
    fun onTurn(commanderId: Int): Boolean {
        val actor = world.getGeneralById(commanderId) ?: return false
        val projection = DeploymentExecutor(world,recorder,topology,metrics).projection()
        val corps = projection?.deployed?.singleOrNull { it.commanderGeneralId == commanderId }
        if (corps == null && CorpsOrder.META_KEY !in actor.meta) return false
        val order = try {
            CorpsOrder.read(actor.meta,topology)?.also { it.requireBinding(requireNotNull(corps),commanderId) }
        } catch (_: IllegalArgumentException) { null }
        if (order == null) { log(commanderId,"출병 명령 상태를 확인할 수 없어 행군을 멈췄습니다.",
            mapOf("stop" to "ORDER_UNAVAILABLE")); return true }
        val refs = linkedMapOf<String, Any?>("orderId" to order.orderId, "destination" to order.destination.canonicalKey)
        val edges = try { LandPassageState.read(world.getState().meta,topology) }
            catch (_: IllegalArgumentException) { null }
        if (edges == null) { log(commanderId,"육상 통행 상태를 확인할 수 없어 출병 행군을 멈췄습니다.",
            refs + ("stop" to "PASSAGE_UNAVAILABLE")); return true }
        val before = try { CorpsMarchState.read(actor.meta,topology,metrics) }
            catch (_: IllegalArgumentException) { null }
        val military = MilitaryPresenceProvider(world,topology,metrics)
        val encounters = CorpsEncounterRecorder(world, recorder, topology, metrics, cells)
        when (val result = CorpsMarchExecutor(world,recorder,topology,metrics,1)
            .advance(order.orderId,commanderId,order.destination,edges) { node ->
                val entry = military.entryAt(commanderId, node, reactions)
                if (entry == LandMarchEntry.ENCOUNTER) {
                    val defenders = encounters.defendersAt(commanderId, node)
                    if (defenders == null && !reactions.interceptsAt(world, commanderId, node) &&
                        !reactions.schemeContact(world, commanderId, node)) LandMarchEntry.UNAVAILABLE else entry
                } else entry
            }) {
            CorpsMarchExecution.AlreadyProcessed -> Unit
            is CorpsMarchExecution.Rejected -> log(commanderId,when(result.reason) {
                CorpsMarchFailure.BATTLE_PENDING -> "조우 처리가 끝나지 않아 출병 행군을 재개할 수 없습니다."
                CorpsMarchFailure.NO_ROUTE -> "출병 목적지까지 통행 가능한 육상 경로가 없습니다."
                else -> "출병 상태를 확인할 수 없어 이동하지 않았습니다."
            }, refs + ("failure" to result.reason.name))
            is CorpsMarchExecution.Applied -> {
                result.movement.reachedNodes.forEach { reactions.onEntered(world, recorder, commanderId, it) }
                val encounterId = if (result.state.checkpoint.stop == LandMarchStop.ENCOUNTER) {
                    val at = result.movement.reachedNodes.last()
                    val defenders = encounters.defendersAt(commanderId, at)
                    if (defenders != null) encounters.record(requireNotNull(corps), defenders, result.state.checkpoint)
                    else {
                        check(reactions.schemeContact(world, commanderId, at)) { "Encounter lost its defender" }
                        null // A scheme contact has no army to seal into grid combat.
                    }
                } else null
                if (before?.checkpoint?.stop == LandMarchStop.ARRIVED && result.state.checkpoint.stop == LandMarchStop.ARRIVED) return true
                log(commanderId,when(result.state.checkpoint.stop) {
                    LandMarchStop.ARRIVED -> "출병 목적지에 도착했습니다."
                    LandMarchStop.BUDGET_EXHAUSTED -> "부대를 거느리고 목적지로 행군하고 있습니다."
                    LandMarchStop.EDGE_BLOCKED -> "통행로가 닫혀 출병 행군을 멈췄습니다."
                    LandMarchStop.ENCOUNTER_UNAVAILABLE -> "진입할 지역의 군사·반응 상태를 확인할 수 없어 출병 행군을 멈췄습니다."
                    LandMarchStop.ENCOUNTER -> if (encounterId == null) "적의 설치 계책을 만나 출병 행군을 멈췄습니다."
                        else "군단이 조우해 출병 행군을 멈췄습니다."
                }, refs + ("stop" to result.state.checkpoint.stop.name) +
                    (encounterId?.let { mapOf("encounterId" to it) } ?: emptyMap()))
            }
        }
        return true
    }
    private fun log(id: Int, text: String, refs: Map<String, Any?>) =
        Records.general(world, id, RecordKind.MARCH_CORPS, text, refs)
}
