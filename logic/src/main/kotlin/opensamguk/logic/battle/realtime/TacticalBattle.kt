package opensamguk.logic.battle.realtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

enum class BattleSide { ATTACKER, DEFENDER }
enum class FormationSlot { VANGUARD, CENTER, LEFT_WING, LEFT_GUARD, RIGHT_WING, RIGHT_GUARD }
enum class UnitKind { INFANTRY, ARCHER, CAVALRY }
enum class BattleOrder { CHARGE, ATTACK, FORMATION, DEFEND, WALL, RETREAT }
enum class RallyPoint { HOME, CENTER, ENEMY }
enum class BattleOutcome { ATTACKER, DEFENDER, DRAW }
enum class TacticalEventKind { ORDER, MOVE, BLOCKED, HIT, ROUT, GATE_DAMAGE, RESOLVED }

data class GeneralStats(
    val id: Int,
    val leadership: Int,
    val strength: Int,
    val intelligence: Int,
    val politics: Int,
    val charisma: Int,
) {
    init {
        require(id > 0)
        require(listOf(leadership, strength, intelligence, politics, charisma).all { it in 0..100 })
    }
}

data class Retinue(
    val id: Int,
    val general: GeneralStats,
    val troops: Int,
    val kind: UnitKind,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val supply: Int,
    val accompaniesCorps: Boolean,
) {
    init {
        require(id > 0 && troops >= 0)
        require(listOf(training, morale, fatigue, supply).all { it in 0..100 })
    }
}

data class Deployment(val side: BattleSide, val slots: Map<FormationSlot, Retinue>) {
    init {
        require(slots.values.map { it.id }.distinct().size == slots.size)
        require(slots.values.all { it.accompaniesCorps && it.troops > 0 })
    }

    fun move(from: FormationSlot, to: FormationSlot): Deployment {
        require(from in slots)
        val next = slots.toMutableMap()
        val displaced = next.remove(to)
        next[to] = next.remove(from)!!
        if (displaced != null) next[from] = displaced
        return copy(slots = next)
    }
}

object BattleDeployment {
    fun default(side: BattleSide, commanderId: Int, retinues: List<Retinue>): Deployment {
        require(commanderId > 0)
        require(retinues.map { it.id }.distinct().size == retinues.size)
        require(retinues.map { it.general.id }.distinct().size == retinues.size)
        val eligible = retinues.filter { it.accompaniesCorps && it.troops > 0 }
        val assigned = linkedMapOf<FormationSlot, Retinue>()
        eligible.firstOrNull { it.general.id == commanderId }?.let { assigned[FormationSlot.CENTER] = it }
        val others = eligible.filter { it.general.id != commanderId }
        val vanguard = others.sortedWith(compareByDescending<Retinue> { it.general.strength }.thenBy { it.general.id }).firstOrNull()
        if (vanguard != null) assigned[FormationSlot.VANGUARD] = vanguard
        val order = listOf(FormationSlot.LEFT_WING, FormationSlot.RIGHT_WING, FormationSlot.LEFT_GUARD, FormationSlot.RIGHT_GUARD)
        others.filter { it.id != vanguard?.id }
            .sortedWith(compareByDescending<Retinue> { it.general.leadership }.thenBy { it.general.id })
            .take(order.size)
            .forEachIndexed { index, retinue -> assigned[order[index]] = retinue }
        return Deployment(side, assigned)
    }
}

data class Battlefield(val id: Int, val kind: String, val rows: List<String>) {
    init {
        require(id in 0..213 && kind in setOf("FIELD", "FORTRESS"))
        require(rows.size == 64 && rows.all { it.length == 64 && it.all { cell -> cell in "PFMRW" } })
        require((kind == "FORTRESS") == rows.any { 'W' in it })
    }

    fun at(row: Int, col: Int): Char = if (row in 0..63 && col in 0..63) rows[row][col] else 'X'
}

data class TacticalUnit(
    val side: BattleSide,
    val slot: FormationSlot,
    val retinue: Retinue,
    val row: Int,
    val col: Int,
    val troops: Int = retinue.troops,
    val morale: Int = (TacticalRules.CANON.initialMoraleBase +
        retinue.general.strength / TacticalRules.CANON.strengthMoraleDivisor +
        retinue.morale / TacticalRules.CANON.retinueMoraleDivisor).coerceIn(0, 200),
    val order: BattleOrder = BattleOrder.FORMATION,
    val rally: RallyPoint = RallyPoint.CENTER,
    val moveWait: Int = 0,
    val attackWait: Int = 0,
    val escaped: Boolean = false,
) {
    init {
        require(row in 0..63 && col in 0..63 && troops in 0..retinue.troops && morale in 0..200)
        require(moveWait >= 0 && attackWait >= 0)
    }

    val alive: Boolean get() = troops > 0 && !escaped
}

data class TacticalCommand(
    val tick: Int,
    val sequence: Long,
    val side: BattleSide,
    val slot: FormationSlot?,
    val order: BattleOrder,
    val rally: RallyPoint = RallyPoint.CENTER,
) {
    init { require(tick >= 0 && sequence >= 0) }
}

data class TacticalEvent(val tick: Int, val kind: TacticalEventKind, val unitId: Int?, val amount: Int = 0)

data class TacticalState(
    val seed: Long,
    val battlefield: Battlefield,
    val tick: Int,
    val units: List<TacticalUnit>,
    val humanSides: Set<BattleSide>,
    val gateRow: Int? = null,
    val gateCol: Int? = null,
    val gateHp: Int = 0,
    val outcome: BattleOutcome? = null,
) {
    init {
        require(tick in 0..TacticalRules.CANON.battleTicks && gateHp >= 0)
        require(units.map { it.retinue.id }.distinct().size == units.size)
        require(units.map { it.row to it.col }.distinct().size == units.size)
        require(units.groupBy { it.side }.values.all { side -> side.map { it.slot }.distinct().size == side.size })
        require((gateRow == null) == (gateCol == null))
        require(gateRow != null || gateHp == 0)
        if (gateRow != null) {
            val col = requireNotNull(gateCol)
            require(gateRow in 0..63 && col in 0..63 && battlefield.at(gateRow, col) == 'W')
        }
    }
}

data class TacticalStep(val state: TacticalState, val events: List<TacticalEvent>, val stateHash: String)

/** Pure fixed-tick rules. Wall/gate and terrain inputs are pinned by the caller's battle ticket. */
object TacticalBattle {
    private val rules get() = TacticalRules.CANON
    private val unitOrder = compareBy<TacticalUnit>({ it.side.ordinal }, { it.slot.ordinal }, { it.retinue.id })

    fun start(seed: Long, field: Battlefield, attacker: Deployment, defender: Deployment,
              humanSides: Set<BattleSide> = emptySet(), gateRow: Int? = null,
              gateCol: Int? = null, gateHp: Int = 0): TacticalState {
        require(attacker.side == BattleSide.ATTACKER && defender.side == BattleSide.DEFENDER)
        val occupied = mutableSetOf<Pair<Int, Int>>()
        require((attacker.slots.values.map { it.id } + defender.slots.values.map { it.id }).distinct().size ==
            attacker.slots.size + defender.slots.size)
        val units = (attacker.slots.map { Triple(BattleSide.ATTACKER, it.key, it.value) } +
            defender.slots.map { Triple(BattleSide.DEFENDER, it.key, it.value) })
            .sortedWith(compareBy<Triple<BattleSide, FormationSlot, Retinue>> { it.first.ordinal }
                .thenBy { it.second.ordinal })
            .map { (side, slot, retinue) ->
                val startCol = if (side == BattleSide.ATTACKER) 3 else 60
                val preferredRow = 6 + slot.ordinal * 10
                val cell = (0..63).flatMap { row -> (0..63).map { col -> row to col } }
                    .filter { (row, col) -> field.at(row, col) in "PF" && (row to col) !in occupied }
                    .minWithOrNull(compareBy<Pair<Int, Int>> { kotlin.math.abs(it.first - preferredRow) + kotlin.math.abs(it.second - startCol) }
                        .thenBy { it.first }.thenBy { it.second })
                    ?: error("no passable deployment tile")
                occupied += cell
                TacticalUnit(side, slot, retinue, cell.first, cell.second)
            }.sortedWith(unitOrder)
        return TacticalState(seed, field, 0, units, humanSides, gateRow, gateCol, gateHp)
    }

    fun step(state: TacticalState, commands: List<TacticalCommand> = emptyList()): TacticalStep {
        require(state.outcome == null) { "battle already resolved" }
        require(commands.all { it.tick == state.tick && it.side in state.humanSides &&
            (it.slot == null || state.units.any { unit -> unit.side == it.side && unit.slot == it.slot }) })
        require(commands.map { it.sequence }.distinct().size == commands.size)
        val events = mutableListOf<TacticalEvent>()
        val unitCommands = mutableMapOf<Int, TacticalCommand>()
        commands.sortedWith(compareBy<TacticalCommand> { it.sequence }.thenBy { it.side.ordinal }.thenBy { it.slot?.ordinal ?: -1 })
            .forEach { command ->
                state.units.filter { it.side == command.side && (command.slot == null || it.slot == command.slot) }
                    .forEach { unit -> unitCommands[unit.retinue.id] = command }
            }
        val ordered = state.units.sortedWith(unitOrder).map { unit ->
            val input = unitCommands[unit.retinue.id]
            val aiOrder = when {
                unit.morale < rules.moraleRetreatBelow -> BattleOrder.RETREAT
                unit.side in state.humanSides -> unit.order
                unit.side == BattleSide.DEFENDER && state.battlefield.kind == "FORTRESS" -> BattleOrder.WALL
                state.tick < 100 -> BattleOrder.FORMATION
                state.tick < 250 -> BattleOrder.ATTACK
                else -> BattleOrder.CHARGE
            }
            if (input != null) events += TacticalEvent(state.tick, TacticalEventKind.ORDER, unit.retinue.id)
            unit.copy(order = if (unit.morale < rules.moraleRetreatBelow) BattleOrder.RETREAT else input?.order ?: aiOrder,
                rally = input?.rally ?: unit.rally,
                moveWait = (unit.moveWait - 1).coerceAtLeast(0), attackWait = (unit.attackWait - 1).coerceAtLeast(0))
        }
        val occupied = ordered.filter { it.alive }.associateBy { it.row to it.col }
        val moves = ordered.filter { it.alive && it.moveWait == 0 }
            .mapNotNull { unit -> nextCell(unit, ordered, state)?.let { unit to it } }
            .filter { (_, cell) -> cell !in occupied }
            .groupBy { it.second }
            .mapValues { (_, requests) -> requests.map { it.first }.minWithOrNull(compareByDescending<TacticalUnit> { movePriority(it) }.thenBy { it.retinue.id })!! }
        val moved = ordered.map { unit ->
            val cell = moves.entries.firstOrNull { it.value.retinue.id == unit.retinue.id }?.key
            if (cell == null) unit else {
                events += TacticalEvent(state.tick, TacticalEventKind.MOVE, unit.retinue.id)
                unit.copy(row = cell.first, col = cell.second, moveWait = moveDelay(unit, state.battlefield.at(cell.first, cell.second)))
            }
        }
        var gateHp = state.gateHp
        val attackSnapshot = moved.sortedWith(unitOrder)
        val damage = mutableMapOf<Int, Long>()
        val fired = mutableSetOf<Int>()
        val attackers = attackSnapshot.filter { it.alive && it.attackWait == 0 }
        for (unit in attackers) {
            val target = attackSnapshot.filter { it.alive && it.side != unit.side }
                .filter { distance(unit, it) <= range(unit) && lineOfSight(unit, it, state.battlefield, state) }
                .minWithOrNull(compareBy<TacticalUnit> { distance(unit, it) }.thenBy { it.retinue.id })
            if (target != null) {
                damage[target.retinue.id] = damage.getOrDefault(target.retinue.id, 0L) + damage(unit, target, state.battlefield)
                fired += unit.retinue.id
            } else if (unit.side == BattleSide.ATTACKER && state.gateRow != null && gateHp > 0 &&
                kotlin.math.abs(unit.row - state.gateRow) + kotlin.math.abs(unit.col - state.gateCol!!) == 1 &&
                unit.order in setOf(BattleOrder.ATTACK, BattleOrder.CHARGE)) {
                gateHp = (gateHp - rules.gateDamagePerAttack).coerceAtLeast(0)
                events += TacticalEvent(state.tick, TacticalEventKind.GATE_DAMAGE, unit.retinue.id,
                    rules.gateDamagePerAttack)
                fired += unit.retinue.id
            }
        }
        val afterCombat = moved.map { unit ->
            val loss = damage.getOrDefault(unit.retinue.id, 0L).coerceAtMost(unit.troops.toLong()).toInt()
            if (loss > 0) events += TacticalEvent(state.tick, TacticalEventKind.HIT, unit.retinue.id, loss)
            val nextMorale = (unit.morale - if (unit.troops == 0) 0 else (loss * 100L / unit.troops).toInt()).coerceAtLeast(0)
            if (unit.morale >= rules.moraleRetreatBelow && nextMorale < rules.moraleRetreatBelow)
                events += TacticalEvent(state.tick, TacticalEventKind.ROUT, unit.retinue.id)
            unit.copy(troops = unit.troops - loss, morale = nextMorale,
                order = if (nextMorale < rules.moraleRetreatBelow && unit.troops > loss) BattleOrder.RETREAT else unit.order,
                attackWait = if (unit.retinue.id in fired) rules.attackIntervalTicks else unit.attackWait)
        }.map { unit ->
            if (unit.order == BattleOrder.RETREAT && ((unit.side == BattleSide.ATTACKER && unit.col <= 1) ||
                    (unit.side == BattleSide.DEFENDER && unit.col >= 62))) unit.copy(escaped = true) else unit
        }
        val nextTick = state.tick + 1
        val outcome = resolve(afterCombat, nextTick)
        if (outcome != null) events += TacticalEvent(state.tick, TacticalEventKind.RESOLVED, null)
        val next = state.copy(tick = nextTick, units = afterCombat, gateHp = gateHp, outcome = outcome)
        return TacticalStep(next, events, stateHash(next))
    }

    fun replay(initial: TacticalState, commands: List<TacticalCommand>, untilTick: Int): TacticalState {
        require(untilTick in initial.tick..rules.battleTicks)
        val byTick = commands.groupBy { it.tick }
        var state = initial
        while (state.tick < untilTick && state.outcome == null) state = step(state, byTick[state.tick].orEmpty()).state
        return state
    }

    fun stateHash(state: TacticalState): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeLong(state.seed)
            out.writeInt(state.battlefield.id)
            out.writeUTF(state.battlefield.kind)
            state.battlefield.rows.forEach { out.writeBytes(it) }
            out.writeInt(state.tick)
            out.writeInt(state.gateRow ?: -1)
            out.writeInt(state.gateCol ?: -1)
            out.writeInt(state.gateHp)
            out.writeInt(state.outcome?.ordinal ?: -1)
            out.writeInt(state.humanSides.size)
            state.humanSides.sortedBy { it.ordinal }.forEach { out.writeInt(it.ordinal) }
            out.writeInt(state.units.size)
            state.units.sortedWith(unitOrder).forEach { unit ->
                out.writeInt(unit.side.ordinal); out.writeInt(unit.slot.ordinal); out.writeInt(unit.retinue.id)
                out.writeInt(unit.retinue.general.id)
                out.writeInt(unit.retinue.general.leadership); out.writeInt(unit.retinue.general.strength)
                out.writeInt(unit.retinue.general.intelligence); out.writeInt(unit.retinue.general.politics)
                out.writeInt(unit.retinue.general.charisma)
                out.writeInt(unit.retinue.troops); out.writeInt(unit.retinue.kind.ordinal)
                out.writeInt(unit.retinue.training); out.writeInt(unit.retinue.morale)
                out.writeInt(unit.retinue.fatigue); out.writeInt(unit.retinue.supply)
                out.writeBoolean(unit.retinue.accompaniesCorps)
                out.writeInt(unit.row); out.writeInt(unit.col); out.writeInt(unit.troops); out.writeInt(unit.morale)
                out.writeInt(unit.order.ordinal); out.writeInt(unit.rally.ordinal)
                out.writeInt(unit.moveWait); out.writeInt(unit.attackWait); out.writeBoolean(unit.escaped)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun nextCell(unit: TacticalUnit, units: List<TacticalUnit>, state: TacticalState): Pair<Int, Int>? {
        if (unit.order in setOf(BattleOrder.DEFEND, BattleOrder.WALL) || !unit.alive) return null
        val enemies = units.filter { it.alive && it.side != unit.side }
        val goal: (Int, Int) -> Boolean = when (unit.order) {
            BattleOrder.RETREAT -> { _, col -> col == if (unit.side == BattleSide.ATTACKER) 0 else 63 }
            BattleOrder.FORMATION -> {
                val col = when (unit.rally) {
                RallyPoint.HOME -> if (unit.side == BattleSide.ATTACKER) 8 else 55
                RallyPoint.CENTER -> 32
                RallyPoint.ENEMY -> if (unit.side == BattleSide.ATTACKER) 55 else 8
                }
                val predicate: (Int, Int) -> Boolean = { _, candidate -> candidate == col }
                predicate
            }
            else -> {
                if (unit.side == BattleSide.ATTACKER && state.gateHp > 0 && state.gateRow != null) {
                    val gateRow = state.gateRow
                    val gateCol = requireNotNull(state.gateCol)
                    val predicate: (Int, Int) -> Boolean = { row, col ->
                        kotlin.math.abs(row - gateRow) + kotlin.math.abs(col - gateCol) == 1
                    }
                    predicate
                } else {
                    val enemy = enemies.minWithOrNull(compareBy<TacticalUnit> { distance(unit, it) }.thenBy { it.retinue.id })
                        ?: return null
                    val predicate: (Int, Int) -> Boolean = { row, col ->
                        kotlin.math.abs(row - enemy.row) + kotlin.math.abs(col - enemy.col) <= range(unit) &&
                            lineOfSight(row, col, enemy.row, enemy.col, state.battlefield, state)
                    }
                    predicate
                }
            }
        }
        if (goal(unit.row, unit.col)) return null
        val occupied = units.filter { it.alive && it.retinue.id != unit.retinue.id }
            .mapTo(mutableSetOf()) { it.row to it.col }
        val queue = ArrayDeque<Pair<Int, Int>>()
        val visited = BooleanArray(64 * 64)
        val firstStep = arrayOfNulls<Pair<Int, Int>>(64 * 64)
        queue.addLast(unit.row to unit.col)
        visited[unit.row * 64 + unit.col] = true
        while (queue.isNotEmpty()) {
            val (row, col) = queue.removeFirst()
            for (next in listOf(row to (col + 1), row to (col - 1), (row + 1) to col, (row - 1) to col)) {
                val (nextRow, nextCol) = next
                if (nextRow !in 0..63 || nextCol !in 0..63 || next in occupied ||
                    !passable(nextRow, nextCol, state)) continue
                val index = nextRow * 64 + nextCol
                if (visited[index]) continue
                visited[index] = true
                firstStep[index] = if (row == unit.row && col == unit.col) next else firstStep[row * 64 + col]
                if (goal(nextRow, nextCol)) return firstStep[index]
                queue.addLast(next)
            }
        }
        return null
    }

    private fun passable(row: Int, col: Int, state: TacticalState): Boolean = when (state.battlefield.at(row, col)) {
        'P', 'F', 'R' -> true
        'M' -> false
        'W' -> state.gateHp == 0 && row == state.gateRow && col == state.gateCol
        else -> false
    }

    private fun movePriority(unit: TacticalUnit): Int = when (unit.retinue.kind) {
        UnitKind.CAVALRY -> 30
        UnitKind.INFANTRY -> 20
        UnitKind.ARCHER -> 10
    }

    private fun moveDelay(unit: TacticalUnit, terrain: Char): Int =
        (when (unit.retinue.kind) {
            UnitKind.CAVALRY -> rules.cavalryMoveTicks
            UnitKind.INFANTRY -> rules.infantryMoveTicks
            UnitKind.ARCHER -> rules.archerMoveTicks
        }) + when (terrain) { 'F' -> rules.forestExtraTicks; 'R' -> rules.riverExtraTicks; else -> 0 }

    private fun distance(a: TacticalUnit, b: TacticalUnit): Int =
        kotlin.math.abs(a.row - b.row) + kotlin.math.abs(a.col - b.col)

    private fun range(unit: TacticalUnit): Int = if (unit.retinue.kind == UnitKind.ARCHER) rules.archerRange else 1

    private fun lineOfSight(a: TacticalUnit, b: TacticalUnit, map: Battlefield, state: TacticalState): Boolean {
        return lineOfSight(a.row, a.col, b.row, b.col, map, state)
    }

    private fun lineOfSight(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int,
                            map: Battlefield, state: TacticalState): Boolean {
        val dr = toRow - fromRow
        val dc = toCol - fromCol
        val steps = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
        return (1 until steps).none { offset ->
            val row = fromRow + dr * offset / steps
            val col = fromCol + dc * offset / steps
            map.at(row, col) == 'W' && !(state.gateHp == 0 && row == state.gateRow && col == state.gateCol)
        }
    }

    private fun damage(attacker: TacticalUnit, defender: TacticalUnit, map: Battlefield): Int {
        val base = (attacker.troops.toLong() * rules.baseDamagePercent / 100).coerceAtLeast(1)
        val triangle = when (attacker.retinue.kind to defender.retinue.kind) {
            UnitKind.CAVALRY to UnitKind.ARCHER -> 100 + rules.cavalryVsArcherPercent
            UnitKind.INFANTRY to UnitKind.CAVALRY -> 100 + rules.infantryVsCavalryPercent
            UnitKind.ARCHER to UnitKind.INFANTRY -> 100 + rules.archerVsInfantryPercent
            else -> 100
        }
        val terrain = when (map.at(defender.row, defender.col)) {
            'F' -> 100 - rules.forestDefensePercent
            else -> 100
        }
        val supply = if (attacker.retinue.supply == 0) 80 else 100
        return (base * (100 + attacker.retinue.training) * (100 + attacker.retinue.general.leadership) *
            triangle * terrain * supply /
            (100L * 200 * 200 * 100 * 100)).coerceIn(1, defender.troops.toLong()).toInt()
    }

    private fun resolve(units: List<TacticalUnit>, tick: Int): BattleOutcome? {
        val attack = units.any { it.side == BattleSide.ATTACKER && it.alive }
        val defense = units.any { it.side == BattleSide.DEFENDER && it.alive }
        if (!attack && !defense) return BattleOutcome.DRAW
        if (!attack) return BattleOutcome.DEFENDER
        if (!defense) return BattleOutcome.ATTACKER
        if (tick < rules.battleTicks) return null
        val attackScore = units.filter { it.side == BattleSide.ATTACKER && it.alive }.sumOf { it.troops.toLong() * it.morale }
        val defenseScore = units.filter { it.side == BattleSide.DEFENDER && it.alive }.sumOf { it.troops.toLong() * it.morale }
        return if (attackScore > defenseScore) BattleOutcome.ATTACKER else BattleOutcome.DEFENDER
    }
}
