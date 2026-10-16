package opensamguk.logic.battle.realtime.soldier

import java.math.BigInteger
import java.util.Collections
import opensamguk.logic.battle.realtime.*
import opensamguk.logic.battle.realtime.space.*

/** S0 factory only: the spatial implementation is owned by C-4a-S. A fresh empty index is required. */
fun interface TacticalV2SoldierSpaceFactory {
    fun create(field: Battlefield, gate: TacticalV2Cell?, params: TacticalV2SpaceParams): TacticalV2Space
}

/** Immutable committed inputs required alongside a checkpoint on restore. */
class TacticalV2BattleContext(
    val seed: Long,
    battlefield: Battlefield,
    val deployment: TacticalV2Deployment,
    facts: List<TacticalV2SoldierSourceFacts>,
    val rules: TacticalRules,
    val combatParams: TacticalV2SoldierCombatParams,
    val spaceParams: TacticalV2SpaceParams,
    humanSides: Set<BattleSide>,
    val gate: TacticalV2Cell? = null,
    val initialGateHp: Int = 0,
    val runBudgetTicks: Long? = null,
) {
    val battlefield = battlefield.copy(rows = Collections.unmodifiableList(ArrayList(battlefield.rows)))
    val facts: List<TacticalV2SoldierSourceFacts> = Collections.unmodifiableList(facts.sortedBy { it.source.key })
    val humanSides: Set<BattleSide> = Collections.unmodifiableSet(HashSet(humanSides))
    init {
        require(deployment.boardId == battlefield.id)
        require(this.facts.map { it.source } == deployment.units)
        require(spaceParams.cellCapacity > 0 && spaceParams.soldierRadius.raw > 0)
        require(spaceParams.sha256.matches(Regex("[0-9a-f]{64}")))
        require(initialGateHp >= 0 && (gate != null || initialGateHp == 0))
        require(gate == null || battlefield.at(gate.row, gate.col) == 'W')
        require(runBudgetTicks == null || runBudgetTicks > 0)
        require(rules.attackIntervalTicks > 0 && rules.moraleScaleMax > 0 &&
            rules.strengthMoraleDivisor > 0 && rules.retinueMoraleDivisor > 0)
        require(listOf(rules.infantryMoveTicks, rules.archerMoveTicks, rules.cavalryMoveTicks).all { it > 0 })
        require(listOf(rules.infantryRange, rules.archerRange, rules.cavalryRange).all { it > 0 })
        require(rules.gateDamagePerAttack > 0 && rules.minimumDamage > 0 && rules.moraleLossPerCasualtyPercent >= 0)
        require(rules.forestExtraTicks >= 0 && rules.riverExtraTicks >= 0)
    }
    val sha256: String by lazy { TacticalV2BattleCodec.contextSha256(this) }
}

class TacticalV2BattleState internal constructor(
    val context: TacticalV2BattleContext,
    val tick: Long,
    soldiers: List<TacticalV2Soldier>,
    val ledger: TacticalV2TroopLedger,
    val gateHp: Int,
    val status: TacticalV2SoldierRunStatus,
) {
    val soldiers: List<TacticalV2Soldier> = Collections.unmodifiableList(ArrayList(soldiers))
    init {
        require(tick >= 0 && gateHp in 0..context.initialGateHp)
        require(this.soldiers.map { it.id } == this.soldiers.map { it.id }.distinct().sorted())
        require(ledger.sources == context.deployment.units)
        var nextId = 1L
        val bySource = this.soldiers.groupBy { it.sourceKey }
        require(bySource.keys == context.facts.map { it.source.key }.toSet())
        context.facts.forEach { fact ->
            val members = bySource.getValue(fact.source.key)
            require(members.size == fact.source.initialTroops)
            members.forEach { require(it.id == nextId++) }
            val balance = ledger.balances.getValue(fact.source.key)
            require(members.count { it.alive } == balance.active)
            require(members.count { !it.alive } == balance.casualties && balance.escaped == 0)
        }
        require(this.soldiers.all { it.hp <= context.combatParams.hitPoints && it.morale <= context.rules.moraleScaleMax })
        val ids = this.soldiers.map { it.id }.toSet()
        require(this.soldiers.all { it.targetId == null || it.targetId in ids })
        require(context.runBudgetTicks?.let { budget ->
            tick <= budget && (status == TacticalV2SoldierRunStatus.BUDGET_EXHAUSTED) == (tick == budget)
        } ?: (status == TacticalV2SoldierRunStatus.RUNNING))
    }

    internal fun evolve(tick: Long = this.tick, soldiers: List<TacticalV2Soldier> = this.soldiers,
                        ledger: TacticalV2TroopLedger = this.ledger, gateHp: Int = this.gateHp,
                        status: TacticalV2SoldierRunStatus = this.status) =
        TacticalV2BattleState(context, tick, soldiers, ledger, gateHp, status)

    fun stateSha256(): String = soldierSha(TacticalV2BattleCodec.encode(this))
}

sealed interface TacticalV2BattleStart {
    data class Ready(val state: TacticalV2BattleState) : TacticalV2BattleStart
    data class Rejected(val reason: String) : TacticalV2BattleStart
}

/** No result/outbox is emitted here. Unresolved victory, exit and surrender policy stays inactive. */
object TacticalV2Battle {
    fun start(context: TacticalV2BattleContext, spaceFactory: TacticalV2SoldierSpaceFactory): TacticalV2BattleStart {
        val space = spaceFactory.create(context.battlefield, context.gate, context.spaceParams)
        context.gate?.let { space.setGateOpen(it, context.initialGateHp == 0) }
        val soldiers = mutableListOf<TacticalV2Soldier>()
        val occupancy = mutableMapOf<TacticalV2Cell, Int>()
        var id = 1L
        for (fact in context.facts) {
            val origin = context.deployment.cells.getValue(fact.source.key)
            val cells = context.deployment.allowedCells.getValue(fact.source.side).sortedWith(
                compareBy<TacticalV2Cell>({ (it.row - origin.row) * (it.row - origin.row) +
                    (it.col - origin.col) * (it.col - origin.col) }, { it.row }, { it.col }))
            var left = fact.source.initialTroops
            for (cell in cells) {
                if (left == 0) break
                if (!space.passable(cellCenter(cell))) continue
                val radius = context.spaceParams.soldierRadius.raw
                if (radius > Fx.ONE / 2) continue
                val diameter = radius * 2
                var row = radius
                while (row <= Fx.ONE - radius && left > 0 &&
                    occupancy.getOrDefault(cell, 0) < context.spaceParams.cellCapacity) {
                    var col = radius
                    while (col <= Fx.ONE - radius && left > 0 &&
                        occupancy.getOrDefault(cell, 0) < context.spaceParams.cellCapacity) {
                        val pos = TacticalV2Pos(Fx(cell.row * Fx.ONE + row), Fx(cell.col * Fx.ONE + col))
                        if (space.canOccupy(pos, null)) {
                            space.place(id, pos)
                            val morale = (context.rules.initialMoraleBase +
                                (fact.general?.strength ?: 0) / context.rules.strengthMoraleDivisor +
                                fact.morale / context.rules.retinueMoraleDivisor)
                                .coerceIn(0, context.rules.moraleScaleMax)
                            soldiers += TacticalV2Soldier(id++, fact.source.key, pos, context.combatParams.hitPoints, morale)
                            occupancy[cell] = occupancy.getOrDefault(cell, 0) + 1
                            left--
                        }
                        col += diameter
                    }
                    row += diameter
                }
            }
            if (left != 0) return TacticalV2BattleStart.Rejected("SPACE_CAPACITY_EXCEEDED")
        }
        return TacticalV2BattleStart.Ready(TacticalV2BattleState(context, 0, soldiers,
            TacticalV2TroopLedger.fromSources(context.deployment.units), context.initialGateHp,
            TacticalV2SoldierRunStatus.RUNNING))
    }

    /** Whole-batch validation before mutation. Duplicate/conflicting selections are rejected, not reordered. */
    fun command(state: TacticalV2BattleState, commands: List<TacticalV2SoldierCommand>,
                controlledKeys: Set<TacticalV2SourceKey>): TacticalV2SoldierDecision {
        fun reject(reason: String) = TacticalV2SoldierDecision(state, false, reason)
        if (state.status != TacticalV2SoldierRunStatus.RUNNING) return reject("NOT_RUNNING")
        val ids = commands.flatMap { it.soldierIds }
        if (ids.distinct().size != ids.size || commands.any { it.soldierIds.isEmpty() }) return reject("INVALID_SELECTION")
        val soldiers = state.soldiers.associateBy { it.id }
        val facts = state.context.facts.associateBy { it.source.key }
        for (command in commands) {
            val selected = command.soldierIds.map { soldiers[it] ?: return reject("INVALID_SELECTION") }
            if (selected.any { !it.alive || it.sourceKey !in controlledKeys }) return reject("INVALID_SELECTION")
            if (command.order !is TacticalV2SoldierOrder.Stop &&
                selected.any { facts.getValue(it.sourceKey).source.stationary }) return reject("INVALID_SELECTION")
            if (command.order is TacticalV2SoldierOrder.Attack) {
                val target = soldiers[command.order.soldierId] ?: return reject("INVALID_SELECTION")
                if (!target.alive || selected.any { facts.getValue(it.sourceKey).source.side ==
                    facts.getValue(target.sourceKey).source.side }) return reject("INVALID_SELECTION")
            }
        }
        if (commands.any { it.order == TacticalV2SoldierOrder.Retreat }) {
            // shortcut: escapeOne and exitZone are not available; remove only after R-2 and B1 are committed.
            return reject("NOT_DECIDED: exitZone / escapeOne")
        }
        if (commands.any { it.order == TacticalV2SoldierOrder.Surrender }) return reject("NOT_DECIDED: surrender")
        val orders = commands.flatMap { cmd -> cmd.soldierIds.map { it to cmd.order } }.toMap()
        val next = state.soldiers.map { soldier ->
            when (val order = orders[soldier.id]) {
                is TacticalV2SoldierOrder.Move -> soldier.copy(goal = order.cell, targetId = null)
                is TacticalV2SoldierOrder.Attack -> soldier.copy(goal = null, targetId = order.soldierId)
                TacticalV2SoldierOrder.Stop -> soldier.copy(goal = null, targetId = null)
                else -> soldier
            }
        }
        return TacticalV2SoldierDecision(state.evolve(soldiers = next), true)
    }

    fun rebuildSpace(state: TacticalV2BattleState, factory: TacticalV2SoldierSpaceFactory): TacticalV2Space {
        val space = factory.create(state.context.battlefield, state.context.gate, state.context.spaceParams)
        state.context.gate?.let { space.setGateOpen(it, state.gateHp == 0) }
        state.soldiers.filter { it.alive }.forEach { space.place(it.id, it.pos) }
        return space
    }

    fun step(previous: TacticalV2BattleState, factory: TacticalV2SoldierSpaceFactory): TacticalV2BattleState {
        if (previous.status != TacticalV2SoldierRunStatus.RUNNING) return previous
        val aiSides = BattleSide.entries.filter { it !in previous.context.humanSides }
        val aiCommands = aiSides.flatMap { TacticalV2Ai.orders(previous, it) }
        val controlled = previous.context.facts.filter { it.source.side in aiSides }.map { it.source.key }.toSet()
        val state = command(previous, aiCommands, controlled).state
        val space = rebuildSpace(state, factory)
        val facts = state.context.facts.associateBy { it.source.key }
        val current = state.soldiers.associateByTo(linkedMapOf()) { it.id }
        val rules = state.context.rules
        for (before in state.soldiers.filter { it.alive }) {
            var soldier = before.copy(attackWait = (before.attackWait - 1).coerceAtLeast(0))
            val fact = facts.getValue(soldier.sourceKey)
            val target = soldier.targetId?.let { current[it] }?.takeIf { it.alive }
            val goal = soldier.goal ?: target?.pos?.cell()
            if (goal != null && !fact.source.stationary &&
                (target == null || !inRange(soldier, target, fact.kind, rules) || !lineOfSight(soldier.pos, target.pos, state))) {
                val ticks = when (fact.kind) {
                    UnitKind.INFANTRY -> rules.infantryMoveTicks
                    UnitKind.ARCHER -> rules.archerMoveTicks
                    UnitKind.CAVALRY -> rules.cavalryMoveTicks
                }
                val nominal = space.nextStep(soldier.id, goal, Fx(Fx.ONE / ticks))
                val tile = nominal.cell()
                val extra = when (state.context.battlefield.at(tile.row, tile.col)) {
                    'F' -> rules.forestExtraTicks
                    'R' -> rules.riverExtraTicks
                    else -> 0
                }
                val next = if (extra == 0) nominal else space.nextStep(soldier.id, goal,
                    Fx(Fx.ONE / (ticks.toLong() + extra)))
                require(space.canOccupy(next, soldier.id)) { "spatial nextStep violated occupancy" }
                space.move(soldier.id, next)
                soldier = soldier.copy(pos = next)
            }
            current[soldier.id] = soldier
        }
        val moved = current.values.toList()
        val movedById = moved.associateBy { it.id }
        val damage = mutableMapOf<Long, Long>()
        val fired = mutableSetOf<Long>()
        var gateHp = state.gateHp
        for (soldier in moved.filter { it.alive && it.attackWait == 0 }) {
            val fact = facts.getValue(soldier.sourceKey)
            val candidates = space.neighbors(soldier.pos, range(fact.kind, rules)).mapNotNull { movedById[it] }
                .filter { it.alive && facts.getValue(it.sourceKey).source.side != fact.source.side &&
                    inRange(soldier, it, fact.kind, rules) && lineOfSight(soldier.pos, it.pos, state) }
            val target = candidates.firstOrNull { it.id == soldier.targetId } ?: candidates.minWithOrNull(
                compareBy<TacticalV2Soldier>({ distanceSquared(soldier.pos, it.pos) }, { it.id }))
            if (target != null) {
                val amount = damage(fact, facts.getValue(target.sourceKey), target.pos, state)
                damage[target.id] = (damage.getOrDefault(target.id, 0L) + amount).coerceAtMost(target.hp.toLong())
                fired += soldier.id
            } else if (fact.source.side == BattleSide.ATTACKER && gateHp > 0 && state.context.gate != null &&
                distanceSquared(soldier.pos, cellCenter(state.context.gate)) <= Fx.ONE * Fx.ONE) {
                gateHp = (gateHp - rules.gateDamagePerAttack).coerceAtLeast(0)
                fired += soldier.id
            }
        }
        if (gateHp == 0 && state.gateHp > 0) state.context.gate?.let { space.setGateOpen(it, true) }
        var ledger = state.ledger
        val after = moved.map { soldier ->
            val loss = damage.getOrDefault(soldier.id, 0).toInt()
            val hp = soldier.hp - loss
            if (soldier.alive && hp == 0) {
                ledger = ledger.takeCasualties(soldier.sourceKey, 1)
                space.remove(soldier.id)
            }
            soldier.copy(hp = hp, status = if (hp == 0) TacticalV2SoldierStatus.INCAPACITATED else soldier.status,
                attackWait = if (soldier.id in fired) rules.attackIntervalTicks else soldier.attackWait)
        }
        val losses = state.context.facts.associate { fact -> fact.source.key to
            (ledger.balances.getValue(fact.source.key).casualties - state.ledger.balances.getValue(fact.source.key).casualties) }
        val afterMorale = after.map { soldier ->
            val activeBefore = state.ledger.balances.getValue(soldier.sourceKey).active
            val moraleLoss = if (activeBefore == 0) 0 else
                (losses.getValue(soldier.sourceKey).toLong() * 100 * rules.moraleLossPerCasualtyPercent / activeBefore)
                    .coerceAtMost(soldier.morale.toLong()).toInt()
            soldier.copy(morale = soldier.morale - moraleLoss)
        }
        val tick = Math.addExact(state.tick, 1)
        return state.evolve(tick = tick, soldiers = afterMorale, ledger = ledger, gateHp = gateHp,
            status = if (tick == state.context.runBudgetTicks) TacticalV2SoldierRunStatus.BUDGET_EXHAUSTED
                else TacticalV2SoldierRunStatus.RUNNING)
    }

    /** B2 capture/production/base/unit policies are unresolved; annihilation alone is not victory. */
    fun victoryDecision(@Suppress("UNUSED_PARAMETER") state: TacticalV2BattleState): String =
        "NOT_DECIDED: captureRadius / captureTicks / captureDecayPerTick / captureDecayGraceTicks / productionMeans / headquarters / victoryUnit"

    /** Tests must provide an execution budget, never a game timeout or an implicit winner. */
    fun runForTest(initial: TacticalV2BattleState, factory: TacticalV2SoldierSpaceFactory): TacticalV2BattleState {
        require(initial.context.runBudgetTicks != null) { "finite test runBudgetTicks required" }
        var state = initial
        while (state.status == TacticalV2SoldierRunStatus.RUNNING) state = step(state, factory)
        return state
    }

    internal fun range(kind: UnitKind, rules: TacticalRules): Fx = Fx(Fx.ONE * when (kind) {
        UnitKind.INFANTRY -> rules.infantryRange
        UnitKind.ARCHER -> rules.archerRange
        UnitKind.CAVALRY -> rules.cavalryRange
    }.coerceAtMost(128)) // Beyond the board diameter, all positions are already in range.

    private fun inRange(a: TacticalV2Soldier, b: TacticalV2Soldier, kind: UnitKind, rules: TacticalRules): Boolean {
        val raw = range(kind, rules).raw
        return distanceSquared(a.pos, b.pos) <= raw * raw
    }

    /** Same cell-sampling wall occlusion as v1; only the pinned, destroyed gate is transparent. */
    private fun lineOfSight(a: TacticalV2Pos, b: TacticalV2Pos, state: TacticalV2BattleState): Boolean {
        val from = a.cell()
        val to = b.cell()
        val dr = to.row - from.row
        val dc = to.col - from.col
        val count = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
        return (1 until count).none { step ->
            val cell = TacticalV2Cell(from.row + dr * step / count, from.col + dc * step / count)
            state.context.battlefield.at(cell.row, cell.col) == 'W' && !(state.gateHp == 0 && cell == state.context.gate)
        }
    }

    private fun damage(attacker: TacticalV2SoldierSourceFacts, defender: TacticalV2SoldierSourceFacts,
                       target: TacticalV2Pos, state: TacticalV2BattleState): Int {
        val rules = state.context.rules
        val triangle = when (attacker.kind to defender.kind) {
            UnitKind.CAVALRY to UnitKind.ARCHER -> 100L + rules.cavalryVsArcherPercent
            UnitKind.INFANTRY to UnitKind.CAVALRY -> 100L + rules.infantryVsCavalryPercent
            UnitKind.ARCHER to UnitKind.INFANTRY -> 100L + rules.archerVsInfantryPercent
            else -> 100L
        }
        val cell = target.cell()
        val terrain = when (state.context.battlefield.at(cell.row, cell.col)) {
            'F' -> 100L - rules.forestDefensePercent
            'R' -> 100L - rules.riverDefensePercent
            else -> 100L
        }
        val supply = if (attacker.provisions == 0) 100L + rules.supplyShortageDamagePercent else 100L
        val value = listOf(state.context.combatParams.damage.toLong(), 100L + attacker.training,
            100L + (attacker.general?.leadership ?: 0), triangle, terrain, supply)
            .fold(BigInteger.ONE) { product, factor -> product * BigInteger.valueOf(factor) }
            .divide(BigInteger.valueOf(100L * 200 * 200 * 100 * 100))
        return value.coerceIn(BigInteger.valueOf(rules.minimumDamage.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }
}
