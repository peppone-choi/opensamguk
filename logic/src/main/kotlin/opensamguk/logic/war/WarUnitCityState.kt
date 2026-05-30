package opensamguk.logic.war

import opensamguk.logic.domain.City
import opensamguk.logic.util.phpRound

/**
 * The MUTABLE working copy of the immutable [City] that backs [WarUnitCity]. The resolver (B1/B2) records
 * [snapshot] as a ChangeRecorder DELTA (never an inline DB write).
 *
 * `wall` is a Double accumulator because PHP `decreaseHP` does the float division `increaseVarWithLimit(
 * 'wall', -damage/20.0, 0)` then `finishBattle` rounds it half-away (`Util::round(wall)`). `def` mirrors
 * the city `def` column (int). agri/comm/secu carry the (dead-code) wealth-decrement target — present for
 * faithfulness even though the path is unreachable (decision #9).
 */
class WarUnitCityState(initial: City) {
    var city: City = initial
        private set

    var defense: Int = initial.defense
        private set
    var wall: Double = initial.wall.toDouble()
        private set
    var agriculture: Double = initial.agriculture.toDouble()
        private set
    var commerce: Double = initial.commerce.toDouble()
        private set
    var security: Double = initial.security.toDouble()
        private set

    fun updateDefense(value: Int) { defense = value }
    fun updateWall(value: Double) { wall = value }

    /** PHP `increaseVarWithLimit('wall', delta, min)` — float add, lower-clamped. */
    fun increaseWallWithLimit(delta: Double, min: Double) {
        wall = maxOf(min, wall + delta)
    }

    /** PHP `heavyDecreaseWealth`-style agri/comm/secu lower-clamped add (the DEAD-CODE wealth block). */
    fun increaseWealthWithLimit(delta: Double, min: Double) {
        agriculture = maxOf(min, agriculture + delta)
        commerce = maxOf(min, commerce + delta)
        security = maxOf(min, security + delta)
    }

    /** Materialize the immutable [City] delta — def/wall round to int columns. */
    fun snapshot(): City = city.copy(
        defense = defense,
        wall = phpRound(wall),
        agriculture = phpRound(agriculture),
        commerce = phpRound(commerce),
        security = phpRound(security),
    )
}
