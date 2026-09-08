package opensamguk.engine.war

import opensamguk.common.constants.UnitCatalog
import opensamguk.common.rng.serializeSeed
import opensamguk.engine.turn.*
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.*
import opensamguk.logic.world.*
import java.security.MessageDigest

/** One reserved deployment turn. All return positions are validated before combat changes anything. */
class BattlefieldTurnHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val catalog: BattlefieldCatalog,
    private val cityAnchors: Map<Int, StrategicNodeRef>,
    private val hiddenSeed: String,
    private val pipelineFor: (TurnGeneral) -> GeneralActionPipeline = { GeneralActionPipeline() },
) {
    data class Result(val allowed: Boolean, val reason: String? = null,
                      val combat: ProcessFieldWarResult? = null, val logs: List<String> = emptyList())

    fun execute(generalId: Int, args: Map<String, Any?>, year: Int, month: Int): Result {
        fun deny(reason: String) = Result(false, reason)
        if (ActiveWorldMap.requireName(world.getState().config, world.getState().meta) != "han-world-v3")
            return deny("이 지도에서는 전장 이동을 지원하지 않습니다.")
        val actor = world.getGeneralById(generalId) ?: return deny("장수를 찾을 수 없습니다.")
        val snapshot = world.generalPositionSnapshot() ?: return deny("전장 위치 정보가 없습니다.")
        val siteId = args["siteId"] as? String ?: return deny("전장 이동 인자가 올바르지 않습니다.")
        if (siteId.isNotEmpty() && actor.crew <= 0) return deny("전투 가능한 병력이 없습니다.")
        val catalogHash = args["catalogHash"] as? String ?: return deny("전장 목록 정보가 없습니다.")
        val revisionText = args["expectedRevision"] as? String ?: return deny("위치 버전 정보가 없습니다.")
        val expected = if (revisionText.isEmpty()) null else revisionText.toLongOrNull()?.takeIf { it > 0 }
            ?: return deny("위치 버전이 올바르지 않습니다.")
        fun request(g: TurnGeneral, expectedRevision: Long?) = BattlefieldMovementRequest(
            snapshot.topologyRevision, snapshot.topologyHash, g.id, g.cityId,
            snapshot.stateFor(g.id), expectedRevision, catalogHash, cityAnchors,
        )
        val decision = if (siteId.isEmpty()) BattlefieldMovementRules.exit(catalog, request(actor, expected))
            else BattlefieldMovementRules.enter(catalog, siteId, request(actor, expected))
        if (decision is BattlefieldMovementResult.Denied) return deny("전장 이동이 거부되었습니다: ${decision.code}")
        decision as BattlefieldMovementResult.Allowed
        if (world.getCityById(actor.cityId) == null) return deny("귀환 도시를 찾을 수 없습니다.")
        val site = catalog.entries[siteId.ifEmpty { snapshot.stateFor(actor.id)!!.battlefield!!.siteId }]!!
        val hostileNations = world.listDiplomacy().filter { it.fromNationId == actor.nationId && it.state == 0 }
            .mapTo(mutableSetOf()) { it.toNationId }
        val occupants = if (siteId.isEmpty()) emptyList() else world.listGenerals().filter { g ->
            val pos = snapshot.stateFor(g.id)
            g.id != actor.id && pos?.battlefield?.siteId == siteId
        }.sortedBy { it.id }
        // Stale or displaced occupants must not be silently ignored or fought in the wrong place.
        for (g in occupants) {
            val p = snapshot.stateFor(g.id)!!
            if (p.battlefield!!.catalogHash != catalog.contentHash || p.node != site.node)
                return deny("전장 주둔 위치가 현재 자료와 일치하지 않습니다.")
        }
        val defenders = occupants.filter { it.nationId != actor.nationId &&
            (it.nationId == 0 || it.nationId in hostileNations) && it.crew > 0 }
        val exits = linkedMapOf<Int, GeneralPositionAssessment>()
        for (g in defenders) {
            if (world.getCityById(g.cityId) == null) return deny("수비대 귀환 도시를 찾을 수 없습니다.")
            val exit = BattlefieldMovementRules.exit(catalog, request(g, snapshot.stateFor(g.id)!!.revision))
            if (exit !is BattlefieldMovementResult.Allowed) return deny("수비대 귀환 경로가 유효하지 않습니다.")
            exits[g.id] = exit.assessment
        }
        if (defenders.isNotEmpty() && actor.crew <= 0) return deny("전투 가능한 병력이 없습니다.")
        fun input(g: TurnGeneral): FieldCombatantInput? = UnitCatalog.byId(g.crewTypeId)?.let {
            FieldCombatantInput(PerTurnOverlay.toLogicGeneral(g), it,
                (world.getNationById(g.nationId)?.tech ?: 0.0).toInt(), pipelineFor(g))
        }
        val attackerInput = if (defenders.isEmpty()) null else input(actor) ?: return deny("공격 부대 병종이 유효하지 않습니다.")
        val defenderInputs = defenders.map { input(it) ?: return deny("수비 부대 병종이 유효하지 않습니다.") }
        val seed = serializeSeed(hiddenSeed, "battlefield", world.worldId.value, year, month,
            world.getState().currentPhase, actor.id, site.id, catalog.contentHash, expected ?: 0L).toString()
        val combat = attackerInput?.let { processFieldWar(seed, it, defenderInputs) }
        val defeated = (combat?.outcome?.defeatedDefenderIds.orEmpty() +
            combat?.defendersAfter.orEmpty().filter { it.crew <= 0 }.map { it.id }).toSet()
        val entered = combat == null || (!combat.outcome.attackerRetreated && defenders.all { it.id in defeated })
        // Pure assessments were checked above. The single-threaded world cannot change between these writes.
        if (entered) check(recorder.applyGeneralPositionAssessment(world, expected, decision.assessment) !is GeneralPositionChangeResult.Denied)
        for (id in defeated) check(recorder.applyGeneralPositionAssessment(world, snapshot.stateFor(id)!!.revision,
            exits.getValue(id)) !is GeneralPositionChangeResult.Denied)
        combat?.let {
            for (post in listOf(it.attackerAfter) + it.defendersAfter) {
                val pre = world.getGeneralById(post.id)!!
                if (PerTurnOverlay.toLogicGeneral(pre) == post) continue
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), post)
                world.applyGeneralDirtyFree(ReservedTurnHandler.applyGeneralPatch(pre, post))
            }
        }
        val logs = mutableListOf<String>()
        fun log(g: TurnGeneral, text: String) {
            logs.add(text)
            world.pushLog(LogEntryDraft("general", "action", text, generalId = g.id, nationId = g.nationId))
        }
        if (combat == null) log(actor, "${site.name} ${if (siteId.isEmpty()) "전장에서 귀환했습니다." else "전장에 진입했습니다."}")
        else {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
            val after = (listOf(combat.attackerAfter) + combat.defendersAfter).associateBy { it.id }
            for (g in listOf(actor) + defenders) {
                val enemies = if (g.id == actor.id) defenders.joinToString(", ") { it.name } else actor.name
                val result = if (g.id == actor.id) { if (entered) "진입 성공" else "진입 실패·귀환" }
                    else if (g.id in defeated) "패퇴·귀환" else "주둔 유지"
                log(g, "${site.name} 전투 · 상대 $enemies · 손실 ${g.crew - after.getValue(g.id).crew} · $result · 기록 $fingerprint")
            }
        }
        return Result(true, combat = combat, logs = logs)
    }
}
