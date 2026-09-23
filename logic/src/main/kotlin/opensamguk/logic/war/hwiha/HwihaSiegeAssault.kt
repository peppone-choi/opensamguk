package opensamguk.logic.war.hwiha

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position
import opensamguk.logic.world.HwihaBattlefieldLayout

/**
 * 縣城 강공 — #858 격자 전장 위에서 성벽 칸을 공격하는 결정론 전투(2026-09-23 사용자 결정: 강공 = 격자 전투).
 *
 * 격자는 조우와 같은 省 추출·배치 구역 규칙([HwihaBattlefieldLayout])을 쓴다. 공격 측은 진입 쪽 배치 구역,
 * 수비병은 먼 쪽 배치 구역의 **성벽 패**다. 이동은 [HwihaGridMovement] 한 단계 동시 이동, 사거리·시야는
 * [HwihaGridReach], 피해·사기·피로는 육상 교전 산식 v1(HwihaGridExchange 와 같은 식)이다. 성벽 패는
 * 움직이지 않고, 방비(wall/wallMax) 비율만큼 방어력이 오른다. 회차 상한 24(승인), 공격 측 퇴각 조건은
 * 조우 기본 계획과 같다(손실 50% 이상, 사기 20 미만). 성벽 패가 모두 무너지면 함락이다.
 *
 * 성벽 패 수·수비대 훈련/통솔·성벽 보정 상한은 [HwihaS3Provisional] 의 임시값이다. 순수 계산이며
 * 실제 병력·縣 소유 정산은 호출자가 한다.
 */
object HwihaSiegeAssault {
    const val RULE_VERSION = 1

    data class Attacker(val bugokId: Int, val troops: Int, val training: Int, val morale: Int, val fatigue: Int,
        val profile: HwihaUnitProfile) {
        init { require(bugokId > 0 && troops > 0 && training in 0..100 && morale in 0..100 && fatigue in 0..100) }
    }

    data class UnitResult(val bugokId: Int, val troops: Int, val morale: Int, val fatigue: Int)

    enum class Outcome { CAPTURED, REPULSED }

    class Result internal constructor(val outcome: Outcome, val rounds: Int, attackers: List<UnitResult>,
        val garrisonRemaining: Int, val replayHash: String) {
        val attackers: List<UnitResult> = Collections.unmodifiableList(attackers.sortedBy { it.bugokId })
    }

    private data class Token(val id: Int, var troops: Int, var morale: Int, var fatigue: Int, var position: Position?,
        val wall: Boolean, val startTroops: Int)

    fun resolve(layout: HwihaBattlefieldLayout, attackers: List<Attacker>, leadership: Int, garrison: Int,
        garrisonMorale: Int, wallBonusPercent: Int): Result {
        require(attackers.isNotEmpty() && attackers.map { it.bugokId }.distinct().size == attackers.size)
        require(garrison >= 0 && garrisonMorale in 0..100 && leadership >= 0)
        require(wallBonusPercent in 0..HwihaS3Provisional.ASSAULT_MAX_WALL_BONUS_PERCENT)
        val order = compareBy<Position> { it.row }.thenBy { it.col }
        val attackerCells = layout.attackerZone.sortedWith(compareBy<Position> { layout.distancesFromEntry.getValue(it) }.then(order))
        val defenderCells = layout.defenderZone.sortedWith(compareByDescending<Position> { layout.distancesFromEntry.getValue(it) }.then(order))
        val byId = attackers.associateBy { it.bugokId }
        val army = attackers.sortedBy { it.bugokId }.mapIndexed { index, unit ->
            Token(unit.bugokId, unit.troops, unit.morale, unit.fatigue, attackerCells.getOrNull(index), false, unit.troops)
        }
        val wallCount = minOf(defenderCells.size, HwihaS3Provisional.ASSAULT_MAX_WALL_TOKENS, garrison)
        val firstWallId = army.maxOf { it.id } + 1
        val walls = (0 until wallCount).map { index ->
            val troops = garrison / wallCount + if (index < garrison % wallCount) 1 else 0
            Token(firstWallId + index, troops, garrisonMorale, 0, defenderCells[index], true, troops)
        }
        val initialArmy = army.sumOf { it.troops.toLong() }
        var rounds = 0
        var outcome: Outcome? = if (walls.isEmpty()) Outcome.CAPTURED else null
        while (outcome == null) {
            rounds++
            move(layout, army, walls, byId, order)
            exchange(layout, army, walls, byId, leadership, wallBonusPercent)
            val remaining = army.sumOf { it.troops.toLong() }
            val weightedMorale = army.sumOf { it.troops.toLong() * it.morale }
            outcome = when {
                walls.all { it.troops == 0 } -> Outcome.CAPTURED
                remaining == 0L -> Outcome.REPULSED
                (initialArmy - remaining) * 100 >= initialArmy * 50 -> Outcome.REPULSED
                weightedMorale < remaining * 20 -> Outcome.REPULSED
                rounds == HwihaBattlePlans.MAX_ROUNDS -> Outcome.REPULSED
                else -> null
            }
        }
        val results = army.map { UnitResult(it.id, it.troops, it.morale, it.fatigue) }
        val garrisonRemaining = walls.sumOf { it.troops }
        return Result(outcome, rounds, results, garrisonRemaining, hash(outcome, rounds, results, garrisonRemaining))
    }

    private fun move(layout: HwihaBattlefieldLayout, army: List<Token>, walls: List<Token>,
        byId: Map<Int, Attacker>, order: Comparator<Position>) {
        val occupied = (army + walls).mapNotNull { it.position }.toHashSet()
        val targets = walls.filter { it.troops > 0 && it.position != null }
        val plans = army.sortedBy { it.id }.mapNotNull { unit ->
            val start = unit.position ?: return@mapNotNull null
            if (unit.troops == 0 || unit.morale == 0 || targets.isEmpty()) return@mapNotNull null
            val profile = byId.getValue(unit.id).profile
            fun inRange(at: Position) = targets.any { HwihaGridReach.canStrike(layout, at, it.position!!, profile.attackRange) }
            if (inRange(start)) return@mapNotNull null
            val previous = hashMapOf<Position, Position?>(start to null)
            val queue = ArrayDeque<Position>().apply { add(start) }
            var goal: Position? = null
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                if (inRange(at)) { goal = at; break }
                for (cell in listOf(Position(at.col, at.row - 1), Position(at.col - 1, at.row), Position(at.col + 1, at.row),
                        Position(at.col, at.row + 1)).sortedWith(order)) {
                    if (cell in layout.distancesFromEntry && cell !in occupied && cell !in previous) { previous[cell] = at; queue.add(cell) }
                }
            }
            if (goal == null) return@mapNotNull null
            val path = mutableListOf<Position>(); var cell: Position = goal
            while (cell != start) { path.add(cell); cell = previous.getValue(cell)!! }
            unit.id to path.asReversed().take(profile.movementSteps)
        }
        val stopped = hashSetOf<Int>()
        for (step in 0 until (plans.maxOfOrNull { it.second.size } ?: 0)) {
            val intents = plans.filter { (id, path) -> path.size > step && id !in stopped }
                .map { (id, path) -> HwihaGridMovement.Intent(id, path[step]) }
            val all = army + walls
            val result = HwihaGridMovement.resolve(layout, all.map { token ->
                HwihaGridMovement.UnitPosition(token.id, token.position,
                    if (token.wall) 0 else byId.getValue(token.id).profile.initiative)
            }, intents)
            val requested = intents.mapTo(hashSetOf()) { it.bugokId }
            for (move in result) {
                if (move.bugokId !in requested) continue
                if (move.outcome != HwihaGridMovement.Outcome.MOVED) stopped.add(move.bugokId)
                army.single { it.id == move.bugokId }.position = move.to
            }
        }
    }

    private fun exchange(layout: HwihaBattlefieldLayout, army: List<Token>, walls: List<Token>, byId: Map<Int, Attacker>,
        leadership: Int, wallBonusPercent: Int) {
        fun nearest(from: Position, candidates: List<Token>, range: Int) = candidates
            .filter { it.troops > 0 && it.position != null && HwihaGridReach.canStrike(layout, from, it.position!!, range) }
            .minWithOrNull(compareBy<Token> { kotlin.math.abs(from.col.toLong() - it.position!!.col) +
                kotlin.math.abs(from.row.toLong() - it.position!!.row) }.thenBy { it.id })
        val losses = HashMap<Int, BigInteger>()
        val active = hashSetOf<Int>()
        // All strikes read the same round-start snapshot (§5.1.1 simultaneous exchange).
        for (unit in army.sortedBy { it.id }) {
            val at = unit.position ?: continue
            if (unit.troops == 0 || unit.morale == 0) continue
            val profile = byId.getValue(unit.id).profile
            val target = nearest(at, walls, profile.attackRange) ?: continue
            val damage = damage(unit.troops, profile.attackPower, byId.getValue(unit.id).training, unit.morale, unit.fatigue,
                leadership, HwihaS3Provisional.ASSAULT_GARRISON_DEFENCE, HwihaS3Provisional.ASSAULT_GARRISON_TRAINING,
                HwihaS3Provisional.ASSAULT_GARRISON_LEADERSHIP, 100 + wallBonusPercent, target.troops)
            losses.merge(target.id, damage.toBigInteger(), BigInteger::add); active += unit.id
        }
        for (wall in walls.sortedBy { it.id }) {
            val at = wall.position ?: continue
            if (wall.troops == 0 || wall.morale == 0) continue
            val target = nearest(at, army, HwihaS3Provisional.ASSAULT_GARRISON_RANGE) ?: continue
            val targetProfile = byId.getValue(target.id)
            val damage = damage(wall.troops, HwihaS3Provisional.ASSAULT_GARRISON_ATTACK,
                HwihaS3Provisional.ASSAULT_GARRISON_TRAINING, wall.morale, wall.fatigue,
                HwihaS3Provisional.ASSAULT_GARRISON_LEADERSHIP, targetProfile.profile.defencePower, targetProfile.training,
                leadership, 100, target.troops)
            losses.merge(target.id, damage.toBigInteger(), BigInteger::add); active += wall.id
        }
        for (token in (army + walls).sortedBy { it.id }) {
            val lost = (losses[token.id] ?: BigInteger.ZERO).min(token.troops.toBigInteger()).toInt()
            if (lost > 0) {
                val moraleLoss = ((lost.toLong() * 100 + token.troops - 1) / token.troops).toInt()
                token.morale = (token.morale - moraleLoss).coerceAtLeast(0)
                token.troops -= lost
                if (token.troops == 0) token.position = null
            }
            if (token.id in active) token.fatigue = (token.fatigue + HwihaGridExchange.FATIGUE_PER_ATTACK).coerceAtMost(100)
        }
    }

    /** 육상 교전 산식 v1. [defenceScalePercent] 는 성벽 보정(100 = 보정 없음)으로 방어력에 곱한다. */
    private fun damage(troops: Int, attack: Int, training: Int, morale: Int, fatigue: Int, leadership: Int,
        defence: Int, defenceTraining: Int, defenceLeadership: Int, defenceScalePercent: Int, targetTroops: Int): Int {
        fun product(vararg factors: Long) = factors.fold(BigInteger.ONE) { value, factor -> value * factor.toBigInteger() }
        val numerator = product(troops.toLong(), attack.toLong(), 100L + training, 50L + morale, 200L - fatigue,
            100L + leadership, 100L)
        val denominator = product(defence.toLong(), 100L + defenceTraining, 100L + defenceLeadership,
            HwihaGridExchange.DAMAGE_DIVISOR.toLong(), 100, 200, defenceScalePercent.toLong())
        return numerator.divide(denominator).max(BigInteger.ONE).min(targetTroops.toBigInteger()).toInt()
    }

    private fun hash(outcome: Outcome, rounds: Int, results: List<UnitResult>, garrison: Int): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("hwihaSiegeAssault:v$RULE_VERSION"); out.writeUTF(outcome.name); out.writeInt(rounds); out.writeInt(garrison)
            results.sortedBy { it.bugokId }.forEach { listOf(it.bugokId, it.troops, it.morale, it.fatigue).forEach(out::writeInt) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
