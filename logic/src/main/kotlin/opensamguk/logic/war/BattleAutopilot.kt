package opensamguk.logic.war.hwiha

import opensamguk.logic.input.*
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position

/** Generates explicit replay inputs from sealed state; never writes or settles a battle. */
class HwihaBattleAutopilot(
    encounter: HwihaCorpsEncounter,
    forces: HwihaEncounterForces,
    private val relations: HwihaEncounterRelations,
    combat: HwihaEncounterCombatProfiles,
    plans: HwihaBattlePlans,
    private val deployment: HwihaEncounterDeployment,
) {
    private val playback=HwihaBattlePlayback(encounter,forces,relations,combat,plans,deployment)
    private val exchange=HwihaGridExchange(encounter,forces,relations,combat,deployment)
    private val original=forces.units.associateBy { it.bugokId }
    private val profiles=combat.profiles.associateBy { it.crewTypeId }
    private val order=compareBy<Position> { it.row }.thenBy { it.col }

    fun next(journal:HwihaBattleJournal):HwihaRoundInput {
        val before=playback.replay(journal)
        require(before.barrier==HwihaBattlePlayback.Barrier.NONE) { "Battle requires resolution before another round" }
        val occupied=before.units.mapNotNullTo(hashSetOf()) { it.position }
        val moves=before.units.sortedBy { it.bugokId }.mapNotNull { unit ->
            val start=unit.position
            if(start==null || unit.troops==0 || unit.morale==0 ||
                before.actions.getValue(original.getValue(unit.bugokId).commanderGeneralId)!=BattlePlanAction.ADVANCE) return@mapNotNull null
            val enemies=enemies(unit,before.units)
            val profile=profiles.getValue(original.getValue(unit.bugokId).crewTypeId)
            fun inRange(at:Position)=enemies.any { HwihaGridReach.canStrike(deployment.layout,at,it.position!!,profile.attackRange) }
            if(enemies.isEmpty() || inRange(start))return@mapNotNull null
            val previous=hashMapOf<Position,Position?> (start to null)
            val queue=ArrayDeque<Position>();queue.add(start)
            var goal:Position?=null
            while(queue.isNotEmpty()) {
                val at=queue.removeFirst()
                if(inRange(at)) { goal=at;break }
                val adjacent=listOf(Position(at.col,at.row-1),Position(at.col-1,at.row),
                    Position(at.col+1,at.row),Position(at.col,at.row+1)).sortedWith(order)
                for(cell in adjacent) if(cell in deployment.layout.distancesFromEntry && cell !in occupied && cell !in previous) {
                    previous[cell]=at;queue.add(cell)
                }
            }
            if(goal==null)return@mapNotNull null
            val reverse=mutableListOf<Position>();var cell=goal
            while(cell!=start) { reverse.add(cell!!);cell=previous.getValue(cell) }
            HwihaGridExchange.MovementPlan(unit.bugokId,reverse.asReversed().take(profile.movementSteps))
        }
        // Use the same simultaneous movement kernel, including collisions, before choosing targets.
        val moved=exchange.resolveRound(before.units,moves,emptyList()).exchange.units
        val attacks=moved.sortedBy { it.bugokId }.mapNotNull { unit ->
            val at=unit.position
            if(at==null || unit.troops==0 || unit.morale==0)return@mapNotNull null
            val range=profiles.getValue(original.getValue(unit.bugokId).crewTypeId).attackRange
            val target=enemies(unit,moved).filter { HwihaGridReach.canStrike(deployment.layout,at,it.position!!,range) }
                .minWithOrNull(compareBy<HwihaGridExchange.UnitState> {
                    kotlin.math.abs(at.col.toLong()-it.position!!.col)+kotlin.math.abs(at.row.toLong()-it.position!!.row)
                }.thenBy { it.bugokId }) ?: return@mapNotNull null
            HwihaGridExchange.AttackIntent(unit.bugokId,target.bugokId)
        }
        return HwihaRoundInput(before.lastResolvedRound+1,moves,attacks)
    }

    private fun enemies(unit:HwihaGridExchange.UnitState,units:List<HwihaGridExchange.UnitState>) = units.filter {
        it.troops>0 && it.position!=null && original.getValue(it.bugokId).commanderGeneralId!=original.getValue(unit.bugokId).commanderGeneralId &&
            relations.isHostile(original.getValue(unit.bugokId).commanderGeneralId,original.getValue(it.bugokId).commanderGeneralId)
    }
}
