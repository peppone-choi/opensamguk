package opensamguk.logic.inheritance

import opensamguk.logic.util.phpRound
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure functions for computing derived inheritance point values.
 * Faithful port of the PHP/TypeScript calculation formulas.
 *
 * All functions are stateless and side-effect-free — they take raw inputs and return
 * the computed point value. The [InheritancePointManager] calls these at merge time.
 */
object InheritancePointCalculator {

    /** COMBAT: 전투 포인트 = warnum * 5 */
    fun computeCombat(warnum: Int): Double = warnum * 5.0

    /** SABOTAGE: 계략 포인트 = firenum * 20 */
    fun computeSabotage(firenum: Int): Double = firenum * 20.0

    /** DEX: 숙련도 포인트 = dexSum * 0.001 (overflow reduction 적용) */
    fun computeDex(dexSum: Double): Double = dexSum * 0.001

    /**
     * BETTING: 내기 포인트 = betwin * 10 * (betwingold / betgold)^2
     * @param betwin 내기 승리 횟수
     * @param betwingold 승리 금액
     * @param betgold 내기 총 금액 (0이면 0 반환)
     */
    fun computeBetting(betwin: Int, betwingold: Double, betgold: Double): Double {
        if (betgold <= 0.0) return 0.0
        return betwin * 10.0 * (betwingold / betgold).pow(2)
    }

    /** MAX_BELONG: 최대 소속 포인트 = max(belong, auxMaxBelong ?: 0) * 10 */
    fun computeMaxBelong(belong: Int, auxMaxBelong: Int?): Double {
        return maxOf(belong, auxMaxBelong ?: 0) * 10.0
    }

    /** UNIFIER: 통일 이벤트 포인트 */
    fun computeUnifier(eventType: UnifierEventType): Double = when (eventType) {
        UnifierEventType.FOUND_NATION -> 250.0
        UnifierEventType.UNIFY -> 2000.0
    }

    /**
     * Apply the coefficient for a given key to a raw value, using PhpRound for parity.
     * e.g. active_action: raw 1 * coefficient 3.0 = 3.0
     */
    fun applyCoefficient(key: InheritanceKey, rawValue: Double): Double {
        return phpRound(rawValue * key.coefficient).toDouble()
    }
}

/** 통일 이벤트 타입 — 건국(+250) 또는 통일(+2000) */
enum class UnifierEventType {
    FOUND_NATION, // che_건국
    UNIFY,        // check_united() 고위 관직
}

/**
 * Simple data holder for rank_data values needed by the inheritance calculator.
 * Mirrors the rank_data table columns consumed by derived key calculations.
 */
data class RankData(
    val warnum: Int = 0,       // 전쟁 참여 횟수
    val firenum: Int = 0,      // 계략 성공 횟수
    val betwin: Int = 0,       // 내기 승리 횟수
    val betgold: Double = 0.0,  // 내기 총 금액
    val betwingold: Double = 0.0, // 내기 승리 금액
)

/**
 * Simple data holder for betting data (when separate from rank_data).
 */
data class BetData(
    val betwin: Int = 0,
    val betgold: Double = 0.0,
    val betwingold: Double = 0.0,
)

/**
 * Game environment interface for the inheritance system.
 * Provides isunited gating and world state access.
 */
interface GameEnv {
    /** isunited != 0 → 천하통일 상태 (포인트 획득/소비 차단) */
    val isunited: Int
}

/**
 * Adapter from the existing [opensamguk.logic.domain.WorldEnv] to [GameEnv].
 * The daemon constructs this at the tick boundary.
 */
data class InheritanceGameEnv(
    override val isunited: Int,
) : GameEnv
