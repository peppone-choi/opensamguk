package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/** A roadside fort has its own owner and siege; taking a county never transfers the fort. */
class HwihaRoadFortSiegeService(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
) {
    enum class Failure(val message: String) {
        INVALID_INPUT("점령할 보루를 골라 주세요."), STATE_UNAVAILABLE("보루 상태를 확인할 수 없습니다."),
        FORT_NOT_FOUND("그 보루를 찾을 수 없습니다."), NOT_AT_GATE("도로 접경에 도착한 군단만 보루를 포위할 수 있습니다."),
        NOT_HOSTILE("적대 중인 세력의 보루만 포위할 수 있습니다."), ALREADY_BESIEGED("이미 포위 중인 보루입니다."),
        ARMY_UNAVAILABLE("출전 중이고 군량이 있는 군단만 보루를 포위할 수 있습니다."),
    }

    fun start(actorId: Int, fortId: String): Failure? {
        val forts = try { HwihaRoadFortState.read(world.getState().meta) }
            catch (_: IllegalArgumentException) { return Failure.STATE_UNAVAILABLE }
        val fort = forts.singleOrNull { it.id == fortId } ?: return Failure.FORT_NOT_FOUND
        val actor = world.getGeneralById(actorId) ?: return Failure.ARMY_UNAVAILABLE
        if (!hostile(actor.nationId, fort.ownerNationId)) return Failure.NOT_HOSTILE
        if (fort.besiegerGeneralId != null) return Failure.ALREADY_BESIEGED
        if (!ready(actorId, actor.nationId, fort)) return Failure.NOT_AT_GATE
        save(forts.map { if (it.id == fortId) it.copy(besiegerNationId = actor.nationId,
            besiegerGeneralId = actorId, siegeProgress = 0) else it })
        HwihaRecords.general(world, actorId, HwihaRecordKind.ROAD_FORT_SIEGE,
            "도로 보루를 포위했습니다.", mapOf("fortId" to fortId, "edgeId" to fort.edgeId))
        return null
    }

    /** One phase of siege pressure. The wall needs three maintained phases; no free county capture transfers it. */
    fun settleBoundary() {
        val forts = try { HwihaRoadFortState.read(world.getState().meta) }
            catch (_: IllegalArgumentException) { return }
        if (forts.none { it.besiegerGeneralId != null }) return
        val next = forts.map { fort ->
            val generalId = fort.besiegerGeneralId ?: return@map fort
            val nationId = checkNotNull(fort.besiegerNationId)
            if (!hostile(nationId, fort.ownerNationId) || !ready(generalId, nationId, fort)) {
                HwihaRecords.general(world, generalId, HwihaRecordKind.ROAD_FORT_SIEGE,
                    "도로 보루의 포위를 풀었습니다.", mapOf("fortId" to fort.id))
                return@map fort.copy(besiegerNationId = null, besiegerGeneralId = null, siegeProgress = 0)
            }
            val progress = fort.siegeProgress + 34
            if (progress < 100) return@map fort.copy(siegeProgress = progress)
            HwihaRecords.general(world, generalId, HwihaRecordKind.ROAD_FORT_SIEGE,
                "도로 보루를 점령했습니다.", mapOf("fortId" to fort.id, "edgeId" to fort.edgeId))
            HwihaRecords.nation(world, nationId, HwihaRecordKind.ROAD_FORT_CAPTURED,
                "도로 보루를 점령했습니다.", mapOf("fortId" to fort.id, "edgeId" to fort.edgeId))
            fort.copy(ownerNationId = nationId, wall = 50, garrison = 0,
                besiegerNationId = null, besiegerGeneralId = null, siegeProgress = 0)
        }
        if (next != forts) save(next)
    }

    private fun ready(generalId: Int, nationId: Int, fort: HwihaRoadFort): Boolean {
        val edge = topology.traversalEdges.singleOrNull { it.id == fort.edgeId } ?: return false
        val position = world.positionOf(generalId) as? StrategicNodeRef.LandProvince ?: return false
        if (position != edge.from && position != edge.to) return false
        val projection = HwihaDeploymentExecutor(world, recorder, topology, metrics).projection() ?: return false
        if (projection.people.singleOrNull { it.id == generalId }?.inBattle != false) return false
        val corps = projection.deployed.singleOrNull { it.commanderGeneralId == generalId && it.nationId == nationId }
            ?: return false
        val units = corps.bugokIds.mapNotNull(world::getBugokById)
        return units.isNotEmpty() && units.sumOf { it.troops } >= maxOf(1, fort.garrison * 2) &&
            units.all { it.provisions >= it.troops }
    }

    private fun hostile(from: Int, to: Int): Boolean = from > 0 && to > 0 && from != to &&
        world.listDiplomacy().any { it.state == 0 &&
            ((it.fromNationId == from && it.toNationId == to) || (it.fromNationId == to && it.toNationId == from)) }

    private fun save(forts: List<HwihaRoadFort>) {
        val value = HwihaRoadFortState.toMetaValue(forts)
        world.setGameEnvValue(HwihaRoadFortState.META_KEY, value)
        recorder.recordKv("game_env", "game_env", HwihaRoadFortState.META_KEY, value)
    }
}
