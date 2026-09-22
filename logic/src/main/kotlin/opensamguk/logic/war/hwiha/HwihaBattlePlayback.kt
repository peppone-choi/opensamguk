package opensamguk.logic.war.hwiha

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import opensamguk.logic.input.*
import opensamguk.logic.world.HwihaBattlefieldGeometry
import opensamguk.logic.world.HwihaBattlefieldLayout

/** Internal tactical input playback. It does not claim card processing, retreat or live resource settlement. */
class HwihaBattlePlayback(
    private val encounter: HwihaCorpsEncounter,
    private val forces: HwihaEncounterForces,
    relations: HwihaEncounterRelations,
    combat: HwihaEncounterCombatProfiles,
    private val plans: HwihaBattlePlans,
    deployment: HwihaEncounterDeployment,
) {
    enum class Barrier { NONE, RETREAT_REQUIRED, DESTRUCTION_REQUIRED, ROUND_LIMIT }
    class Result(val journal: HwihaBattleJournal, units: List<HwihaGridExchange.UnitState>,
        actions: Map<Int,BattlePlanAction>, triggered: Set<HwihaBattleConditions.CommandKey>,
        frames: List<HwihaGridExchange.RoundResult>, val barrier: Barrier) {
        val units = Collections.unmodifiableList(ArrayList(units))
        val actions = Collections.unmodifiableMap(java.util.TreeMap(actions))
        val triggered = Collections.unmodifiableSet(LinkedHashSet(triggered))
        val frames = Collections.unmodifiableList(ArrayList(frames))
        val lastResolvedRound: Int get() = journal.rounds.size
    }
    private val exchange = HwihaGridExchange(encounter,forces,relations,combat,deployment)
    private val conditions = HwihaBattleConditions(encounter,forces,plans)
    private val commanders = forces.units.associate { it.bugokId to it.commanderGeneralId }
    val contextHash: String
    init {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value:String) { val b=value.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b) }
            fun position(value:HwihaBattlefieldGeometry.Position) { out.writeInt(value.col);out.writeInt(value.row) }
            text("hwihaBattlePlayback:v1");text(encounter.encounterId);text(forces.snapshotId);text(relations.snapshotId);text(plans.snapshotId)
            out.writeInt(combat.rulesVersion);text(combat.rulesContentHash)
            out.writeInt(combat.profiles.size)
            combat.profiles.forEach { p -> listOf(p.crewTypeId,p.movementSteps,p.attackRange,p.attackPower,p.defencePower,p.initiative).forEach(out::writeInt) }
            out.writeInt(HwihaBattlefieldGeometry.RULE_VERSION);out.writeInt(HwihaBattlefieldLayout.RULE_VERSION)
            out.writeInt(HwihaEncounterDeployment.RULE_VERSION);out.writeInt(HwihaGridMovement.RULE_VERSION)
            out.writeInt(HwihaGridExchange.RULE_VERSION);out.writeInt(HwihaGridReach.RULE_VERSION)
            val geometry=deployment.layout.geometry
            text(geometry.provinceId);text(geometry.topologyRevision);text(geometry.topologyHash);text(geometry.tilesContentHash)
            listOf(geometry.originCol,geometry.originRow,geometry.width,geometry.height).forEach(out::writeInt)
            out.writeInt(geometry.cells.size)
            geometry.cells.forEach { position(it.position);text(it.terrain);out.writeInt(it.source.col);out.writeInt(it.source.row);out.writeChar(it.source.terrainCode.code) }
            text(deployment.layout.approachProvinceId)
            out.writeInt(deployment.layout.distancesFromEntry.size)
            deployment.layout.distancesFromEntry.forEach { (cell,distance) -> position(cell);out.writeInt(distance) }
            for(zone in listOf(deployment.layout.attackerZone,deployment.layout.defenderZone)) { out.writeInt(zone.size);zone.forEach(::position) }
            out.writeInt(deployment.tokens.size)
            deployment.tokens.forEach { token ->
                out.writeInt(token.commanderGeneralId);text(token.orderId);out.writeInt(token.bugokId)
                out.writeBoolean(token.position!=null);token.position?.let(::position)
            }
        }
        contextHash=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun initialJournal()=HwihaBattleJournal(encounter.encounterId,contextHash,emptyList())

    fun append(journal:HwihaBattleJournal,input:HwihaRoundInput):Result {
        require(journal.encounterId==encounter.encounterId && journal.contextHash==contextHash)
        return replay(journal.append(input))
    }
    fun replay(journal:HwihaBattleJournal):Result {
        require(journal.encounterId==encounter.encounterId && journal.contextHash==contextHash) { "Battle journal context differs" }
        var units=exchange.initialUnits
        val actions=plans.plans.associate { it.commanderGeneralId to it.initialAction }.toMutableMap()
        var triggered:Set<HwihaBattleConditions.CommandKey> = emptySet()
        val frames=mutableListOf<HwihaGridExchange.RoundResult>()
        var barrier=Barrier.NONE
        for(input in journal.rounds) {
            require(barrier==Barrier.NONE) { "A battle resolution barrier requires handling before another round" }
            require(input.movements.all { it.path.isEmpty() || actions[commanders[it.bugokId]]==BattlePlanAction.ADVANCE }) {
                "Only an advancing commander may issue a movement path"
            }
            val frame=exchange.resolveRound(units,input.movements,input.attacks)
            frames.add(frame);units=frame.exchange.units
            val state=units.associateBy { it.bugokId }
            if(forces.commanders.any { commander -> forces.units.filter { it.commanderGeneralId==commander.generalId }
                    .all { state.getValue(it.bugokId).troops==0 } }) {
                barrier=Barrier.DESTRUCTION_REQUIRED
            } else {
                val evaluated=conditions.evaluate(input.round,units,triggered)
                triggered=evaluated.triggered
                evaluated.activations.forEach { actions[it.commanderGeneralId]=it.action }
                barrier=when {
                    actions.values.any { it==BattlePlanAction.RETREAT } -> Barrier.RETREAT_REQUIRED
                    input.round==HwihaBattlePlans.MAX_ROUNDS -> Barrier.ROUND_LIMIT
                    else -> Barrier.NONE
                }
            }
        }
        return Result(journal,units,actions,triggered,frames,barrier)
    }
}
