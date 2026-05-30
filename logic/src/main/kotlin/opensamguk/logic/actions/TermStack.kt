package opensamguk.logic.actions

import opensamguk.logic.domain.LastTurn

/**
 * Outcome of one [addTermStack] poll.
 *
 * @property ready  true when the command may run THIS turn (term reached preReqTurn at the live capset)
 * @property resultTurn  the [LastTurn] to write as the actor's result-turn (`setResultTurn`). When
 *   [ready] is true it equals the prior turn (no mutation); otherwise it is the advanced/reset stack.
 */
data class TermStackResult(val ready: Boolean, val resultTurn: LastTurn)

/**
 * Capset-seq term-stack poll — byte-faithful port of the CUSTOM `addTermStack` shared by
 * 감축/증축/천도 (`che_감축.php:98-133`, `che_천도.php:131-174`; identical algorithm) layered over
 * the base no-stack short-circuit (`BaseCommand.php:423-425`).
 *
 * The 4-field [LastTurn] `(command, arg, term, seq)` carries the in-flight stack; `seq` snapshots the
 * NATION `capset` at the time the stack opened. Any 감축/증축/천도 success bumps the nation capset
 * (see [bumpCapset]), so an in-flight stack whose `seq < capset` is stale and resets.
 *
 * PHP branch ORDER is load-bearing — command/arg first, then seq, then term:
 *  1. `preReqTurn == 0` → ready immediately (base short-circuit; no stack).
 *  2. `lastTurn.command != command || lastTurn.arg !== arg` → reset `LastTurn(command, arg, 1, capset)`.
 *  3. `lastTurn.seq < capset` → reset `LastTurn(command, arg, 1, capset)`.
 *  4. `lastTurn.term < preReqTurn` → advance `LastTurn(command, arg, term+1, capset)`.
 *  5. else → ready (resultTurn = the prior turn, untouched).
 */
fun addTermStack(
    lastTurn: LastTurn,
    command: String,
    arg: Map<String, Any?>?,
    preReqTurn: Int,
    capset: Int,
): TermStackResult {
    // BaseCommand.php:423-425 — a no-stack command is always ready.
    if (preReqTurn == 0) {
        return TermStackResult(ready = true, resultTurn = lastTurn)
    }

    // che_감축.php:101 — command or arg differs → fresh stack at the live capset.
    if (lastTurn.command != command || lastTurn.arg != arg) {
        return TermStackResult(
            ready = false,
            resultTurn = LastTurn(command, arg, term = 1, seq = capset),
        )
    }

    // che_감축.php:111 — a recent 천도/감축/증축 bumped capset since this stack opened → reset.
    // PHP compares against `getSeq()`, which is null only for legacy/default turns; treat null as 0.
    if ((lastTurn.seq ?: 0) < capset) {
        return TermStackResult(
            ready = false,
            resultTurn = LastTurn(command, arg, term = 1, seq = capset),
        )
    }

    // che_감축.php:122 — still charging.
    if ((lastTurn.term ?: 0) < preReqTurn) {
        return TermStackResult(
            ready = false,
            resultTurn = LastTurn(command, arg, term = (lastTurn.term ?: 0) + 1, seq = capset),
        )
    }

    // che_감축.php:132 — term reached preReqTurn at the live capset → ready.
    return TermStackResult(ready = true, resultTurn = lastTurn)
}

/**
 * The success-path result turn — `che_감축.php:206` `new LastTurn($this->getName(), $this->arg, 0)`:
 * term reset to 0 and seq OMITTED (null). The delete-on-default [LastTurn.toRaw] then drops `seq`.
 */
fun onTermStackSuccess(command: String, arg: Map<String, Any?>?): LastTurn =
    LastTurn(command, arg, term = 0)

/**
 * Bump the nation capset on any 감축/증축/천도 success — invalidates the in-flight stacks of all three
 * (their `seq` now trails the new capset). PHP increments `nation['capset']` by one on a successful
 * capital change.
 */
fun bumpCapset(capset: Int): Int = capset + 1
