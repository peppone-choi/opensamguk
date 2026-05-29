package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.domain.LastTurn

/**
 * The per-command nation-command state captured at execution time — the Kotlin analogue of the
 * PHP NationCommand instance fields `$lastTurn` / `$resultTurn` (NationCommand.php:9-10). The
 * `GeneralActionDefinition` itself is stateless (one shared instance per command class); the
 * mutable turn-state lives here, created per-poll via [NationCommand.newState].
 *
 * `resultTurn` starts as a copy of `lastTurn` (`$lastTurn->duplicate()`, NationCommand.php:14);
 * the term-stack / success path overwrites it via [TermStack].
 */
class NationCommandState(
    val lastTurn: LastTurn,
    var resultTurn: LastTurn,
)

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/Command/NationCommand.php` base — the shared
 * substrate behind the 6+2 nation-internal commands (감축/증축/발령/포상/국호변경/국기변경/천도/무작위수도이전).
 *
 * A `NationCommand` is a stateless [GeneralActionDefinition]; the per-turn `lastTurn`/`resultTurn`
 * state is carried by [NationCommandState] ([newState]). The base owns:
 *  - the per-NATION `next_execute_{actionName}` KV protocol ([nextExecuteKey] / [computeNextAvailable]),
 *    a faithful transcription of NationCommand.php:30-57 (DEAD for the P2 set — every command has
 *    postReqTurn==0 — but part of the contract, research OQ12),
 *  - the shared EXP/DED magnitude `5 * (getPreReqTurn() + 1)` ([expDedMagnitude]) every nation
 *    command grants its actor on success (che_감축.php:167-168 etc.).
 *
 * `getPreReqTurn`/`getPostReqTurn` mirror the PHP abstract pair (BaseCommand.php:481-482); the
 * default `getPostReqTurn` is 0 (the entire P2 nation set returns 0).
 */
abstract class NationCommand : GeneralActionDefinition {

    override val category: String get() = "국가"

    /** `static::$actionName` — the SPACED action name; equals [name] (the registry display name). */
    val actionName: String get() = name

    /** BaseCommand.php:481 `getPreReqTurn()`. */
    abstract fun getPreReqTurn(): Int

    /** BaseCommand.php:482 `getPostReqTurn()` — 0 for every P2 nation command. */
    open fun getPostReqTurn(): Int = 0

    /**
     * NationCommand.php:12-16 — seed a fresh per-turn state from the actor's last-turn.
     * `resultTurn = $lastTurn->duplicate()` — [LastTurn] is an immutable value, so the duplicate
     * is the same value.
     */
    fun newState(lastTurn: LastTurn): NationCommandState =
        NationCommandState(lastTurn = lastTurn, resultTurn = lastTurn)

    /** NationCommand.php:30-34 — `next_execute_{actionName}`. */
    fun nextExecuteKey(): String = "next_execute_$actionName"

    /**
     * The actor's per-success EXP/DED grant magnitude — `5 * (getPreReqTurn() + 1)` (che_감축.php:167).
     * 감축/증축 → 30, 국호변경/국기변경 → 5, 무작위 수도 이전 → 10, 천도 → variable (distance×2 preReqTurn).
     */
    fun expDedMagnitude(): Int = 5 * (getPreReqTurn() + 1)

    /**
     * NationCommand.php:45-57 `setNextAvailable($yearMonth=null)` default-arg math — only fires when
     * `getPostReqTurn()` is truthy; otherwise the KV is never written (returns null here). When it
     * does fire: `joinYearMonth(year, month) + getPostReqTurn() - getPreReqTurn()`.
     */
    fun computeNextAvailable(year: Int, month: Int): Int? {
        if (getPostReqTurn() == 0) return null
        return joinYearMonth(year, month) + getPostReqTurn() - getPreReqTurn()
    }

    companion object {
        /** PHP `Util::joinYearMonth(year, month)` (Util.php:709-711) = `year*12 + month - 1`. */
        fun joinYearMonth(year: Int, month: Int): Int = year * 12 + month - 1
    }
}
