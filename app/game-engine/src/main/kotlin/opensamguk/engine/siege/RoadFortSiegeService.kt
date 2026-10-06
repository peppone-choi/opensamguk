package opensamguk.engine.siege

import java.util.HexFormat
import opensamguk.engine.campaign.*

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole
import opensamguk.logic.world.*

/** A roadside fort has its own owner and siege; taking a county never transfers the fort. */
class RoadFortSiegeService(
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
        val forts = try { RoadFortState.read(world.getState().meta) }
            catch (_: IllegalArgumentException) { return Failure.STATE_UNAVAILABLE }
        val fort = forts.singleOrNull { it.id == fortId } ?: return Failure.FORT_NOT_FOUND
        val actor = world.getGeneralById(actorId) ?: return Failure.ARMY_UNAVAILABLE
        if (!hostile(actor.nationId, fort.ownerNationId)) return Failure.NOT_HOSTILE
        if (fort.besiegerGeneralId != null) return Failure.ALREADY_BESIEGED
        if (!ready(actorId, actor.nationId, fort)) return Failure.NOT_AT_GATE
        save(forts.map { if (it.id == fortId) it.copy(besiegerNationId = actor.nationId,
            besiegerGeneralId = actorId, siegeProgress = 0) else it })
        Records.general(world, actorId, RecordKind.ROAD_FORT_SIEGE,
            "도로 보루를 포위했습니다.", mapOf("fortId" to fortId, "edgeId" to fort.edgeId))
        return null
    }

    /** One phase of siege pressure. The wall needs three maintained phases; no free county capture transfers it. */
    fun settleBoundary() {
        val forts = try { RoadFortState.read(world.getState().meta) }
            catch (_: IllegalArgumentException) { return }
        if (forts.none { it.besiegerGeneralId != null }) return
        val captures = mutableListOf<Pair<RoadFort, RoadFort>>()
        val next = forts.map { fort ->
            val generalId = fort.besiegerGeneralId ?: return@map fort
            val nationId = checkNotNull(fort.besiegerNationId)
            if (!hostile(nationId, fort.ownerNationId) || !ready(generalId, nationId, fort)) {
                Records.general(world, generalId, RecordKind.ROAD_FORT_SIEGE,
                    "도로 보루의 포위를 풀었습니다.", mapOf("fortId" to fort.id))
                return@map fort.copy(besiegerNationId = null, besiegerGeneralId = null, siegeProgress = 0)
            }
            val progress = fort.siegeProgress + 34
            if (progress < 100) return@map fort.copy(siegeProgress = progress)
            Records.general(world, generalId, RecordKind.ROAD_FORT_SIEGE,
                "도로 보루를 점령했습니다.", mapOf("fortId" to fort.id, "edgeId" to fort.edgeId))
            Records.nation(world, nationId, RecordKind.ROAD_FORT_CAPTURED,
                "도로 보루를 점령했습니다.", mapOf("fortId" to fort.id, "edgeId" to fort.edgeId))
            val captured = fort.copy(ownerNationId = nationId, wall = 50, garrison = 0,
                besiegerNationId = null, besiegerGeneralId = null, siegeProgress = 0)
            if (captured.ownerNationId != fort.ownerNationId) captures += fort to captured
            captured
        }
        if (next != forts) {
            save(next)
            val state = world.getState()
            captures.forEach { (before, after) ->
                // EventKey coordinates exclude '@' and ','; encode the exact site id losslessly.
                val siteCoordinates = HexFormat.of().formatHex(before.id.toByteArray(Charsets.UTF_8))
                    .chunked(128).toTypedArray()
                world.recordEvent(
                    kind = EventKind.ROAD_FORT_CAPTURED,
                    audience = AudienceTarget.Public,
                    eventKey = EventKey.derive(EventKind.ROAD_FORT_CAPTURED.code,
                        world.worldId.value.toString(), state.currentYear.toString(),
                        state.currentMonth.toString(), state.currentPhase.toString(),
                        *siteCoordinates,
                        before.ownerNationId.toString(), after.ownerNationId.toString()),
                    refs = mapOf(
                        RefRole.ROAD_FORT to EventRef.RoadFort(after.id),
                        RefRole.FROM_NATION to EventRef.Nation(before.ownerNationId),
                        RefRole.TO_NATION to EventRef.Nation(after.ownerNationId),
                    ),
                )
            }
        }
    }

    private fun ready(generalId: Int, nationId: Int, fort: RoadFort): Boolean {
        val edge = topology.traversalEdges.singleOrNull { it.id == fort.edgeId } ?: return false
        val position = world.positionOf(generalId) as? StrategicNodeRef.LandProvince ?: return false
        if (position != edge.from && position != edge.to) return false
        val projection = DeploymentExecutor(world, recorder, topology, metrics).projection() ?: return false
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

    private fun save(forts: List<RoadFort>) {
        val value = RoadFortState.toMetaValue(forts)
        world.setGameEnvValue(RoadFortState.META_KEY, value)
        recorder.recordKv("game_env", "game_env", RoadFortState.META_KEY, value)
    }
}
