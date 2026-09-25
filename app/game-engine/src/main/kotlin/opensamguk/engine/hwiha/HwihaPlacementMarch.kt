package opensamguk.engine.hwiha

import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PlacementMarch

import opensamguk.logic.domestic.PlacementTarget

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

/**
 * 배치 카드의 부임 행군(§4 「카드는 자기 턴마다 지도 위를 실제로 이동해 부임」). 발령 부임 행군과 같은 경로·진행·조우 경계를
 * 쓰고, 진행은 카드 장수 meta `hwihaPlacementMarch` 에, 도착은 `hwihaPlacement.active.arrivedAt` 에 적는다.
 *
 * 목적지: 縣令 = 그 縣治 城의 省, 사자 = 상대 세력 수도의 省, 정찰 = 지정 省, 군단장 = 주인 장수의 현재 省(주인이 움직이면
 * 다시 경로를 잡는다). 이미 목적지에 서 있으면 행군 없이 도착이다.
 */
class HwihaPlacementMarchTurn(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    /** 반응 기록(요격·회피) 판정 — 군단·발령 행군과 같은 정책을 쓴다. 해석기가 없는 동안은 막지 않는다. */
    private val reactions: HwihaMarchReactionPolicy = HwihaMarchReactionPolicy.NON_BLOCKING,
) {
    /** @return 이 장수의 이동 단계를 배치 행군이 맡았으면 true. */
    fun onTurn(generalId: Int): Boolean {
        if (world.ruleProfile != RuleProfile.HWIHA) return false
        val card = world.getGeneralById(generalId) ?: return false
        val active = try { PlacementState.read(card.meta)?.active } catch (_: IllegalArgumentException) { null } ?: return false
        // A dispatched human assignment owns movement; placements only move NPC cards (validated at intake and activation).
        if (HwihaCountyAssignment.META_KEY in card.meta) return false
        val destination = destinationOf(active.order) ?: run {
            log(generalId, "${active.order.post.label} 자리의 위치를 확인할 수 없어 부임 행군을 멈췄습니다."); return true
        }
        val positions = world.generalPositionSnapshot() ?: return true
        val position = positions.stateFor(generalId) ?: return true
        if (position.battlefield != null) return true
        if (positions.topologyRevision != topology.topologyRevision || positions.topologyHash != topology.contentHash) return true
        val now = world.hwihaNow()
        if (position.node == destination) {
            if (active.arrivedAt == null) {
                arrive(generalId, active, now, clearMarch = true)
                log(generalId, "${active.order.post.label} 자리에 부임했습니다.")
            }
            return true
        }
        // Away from the post (the corps owner moved, or the card was displaced): not seated until it returns.
        if (active.arrivedAt != null) arrive(generalId, active, null, clearMarch = false)
        val edges = try { HwihaLandPassageState.read(world.getState().meta, topology)
            ?.let { HwihaRoadFortPassage.forNation(world, it, card.nationId) } }
            catch (_: IllegalArgumentException) { null }
        if (edges == null) { log(generalId, "육상 통행 상태를 확인할 수 없어 부임 행군을 멈췄습니다."); return true }
        val old = try { PlacementMarch.read(card.meta, topology, metrics) } catch (_: IllegalArgumentException) {
            log(generalId, "부임 행군 상태를 확인할 수 없어 이동하지 않았습니다."); return true
        }?.takeIf { it.requestId == active.order.requestId }
        if (old != null && old.checkpoint.lastAdvancedAt >= now) return true
        if (old?.checkpoint?.stop == LandMarchStop.ENCOUNTER) {
            log(generalId, "조우 처리가 끝나지 않아 부임 행군을 재개할 수 없습니다."); return true
        }
        val retained = old?.checkpoint?.takeIf {
            it.path.nodeKeys.last() == destination.canonicalKey && it.path.nodeKeys[it.cursor.edgeIndex] == position.node.canonicalKey
        }
        val path = retained?.path ?: when (val result = StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(position.node, destination, 1), edges, metrics)) {
            is LandMarchPathResult.Resolved -> result.path
            is LandMarchPathResult.Denied -> { log(generalId, "부임지까지 통행 가능한 육상 경로가 없습니다."); return true }
        }
        val military = HwihaMilitaryPresenceProvider(world, topology, metrics)
        val movement = when (val result = LandMarchProgress.advance(topology, metrics, edges, path,
            retained?.cursor ?: LandMarchCursor(path.pathHash), position.node, 1, LandMarchMetricSnapshot.NORMAL_BUDGET_MM) { node ->
                military.entryAt(generalId, node, reactions) }) {
            is LandMarchAdvance.Advanced -> result
            is LandMarchAdvance.Rejected -> { log(generalId, "부임 행군 상태를 확인할 수 없어 이동하지 않았습니다."); return true }
        }
        if (position.revision > Long.MAX_VALUE - movement.reachedNodes.size) return true
        for (node in movement.reachedNodes) {
            check(recorder.moveGeneral(world, generalId, node) is GeneralPositionChangeResult.Changed) {
                "Validated placement march position transition was rejected"
            }
            reactions.onEntered(world, recorder, generalId, node)
        }
        val march = PlacementMarch(active.order.requestId, HwihaMarchCheckpoint(path, movement.cursor, now, movement.stop))
        val before = checkNotNull(world.getGeneralById(generalId))
        world.updateGeneralMeta(recorder, before, before.meta.withKey(PlacementMarch.META_KEY, march.toMetaValue()))
        when (movement.stop) {
            LandMarchStop.ARRIVED -> {
                arrive(generalId, active, now, clearMarch = true)
                log(generalId, "${active.order.post.label} 자리에 부임했습니다.")
            }
            LandMarchStop.BUDGET_EXHAUSTED -> log(generalId, "${active.order.post.label} 자리로 행군하고 있습니다.")
            LandMarchStop.EDGE_BLOCKED -> log(generalId, "통행로가 닫혀 부임 행군을 멈췄습니다.")
            LandMarchStop.ENCOUNTER_UNAVAILABLE -> log(generalId, "진입할 지역의 군사·반응 상태를 확인할 수 없어 부임 행군을 멈췄습니다.")
            LandMarchStop.ENCOUNTER -> log(generalId, "조우가 발생해 부임 행군을 멈췄습니다.")
        }
        return true
    }

    private fun destinationOf(order: PlacementOrder): StrategicNodeRef.LandProvince? = when (val target = order.target) {
        is PlacementTarget.County -> world.landNodeOfCity(target.countyId) as? StrategicNodeRef.LandProvince
        is PlacementTarget.Nation -> world.getNationById(target.nationId)?.capitalCityId
            ?.let { world.landNodeOfCity(it) as? StrategicNodeRef.LandProvince }
        is PlacementTarget.Province -> StrategicNodeRef.LandProvince(target.provinceId).takeIf { topology.containsNode(it) }
        PlacementTarget.None -> world.positionOf(order.ownerGeneralId) as? StrategicNodeRef.LandProvince
    }

    private fun arrive(generalId: Int, active: ActivePlacement, at: HwihaPhase?, clearMarch: Boolean) {
        val before = checkNotNull(world.getGeneralById(generalId))
        var meta = before.meta.withKey(PlacementState.META_KEY,
            PlacementState(active.copy(arrivedAt = at), PlacementState.read(before.meta)?.pending).toMetaValue())
        if (clearMarch) meta = meta - PlacementMarch.META_KEY
        world.updateGeneralMeta(recorder, before, meta)
        world.syncScoutPosts(recorder, active.order.ownerGeneralId)
    }

    private fun log(generalId: Int, text: String) {
        world.pushLog(LogEntryDraft(scope = "general", category = "action", text = text, generalId = generalId,
            nationId = world.getGeneralById(generalId)?.nationId))
    }
}
