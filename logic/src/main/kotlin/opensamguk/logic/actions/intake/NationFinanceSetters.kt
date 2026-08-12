package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General

/**
 * Pure-logic resolvers for the 내무부 (nation-finance) setters — faithful ports of the PHP
 * `sammo/API/Nation/Set*.php` `launch()` bodies (the validation + permission gate + the value to
 * write). NO Spring/DB/world here: each resolver takes the acting [General] (+ the env inputs the
 * PHP reads) and returns a [FinanceSetterOutcome] the engine handler applies (world mutate +
 * ChangeRecorder delta). This keeps `:logic` pure and the handler thin.
 *
 * Validation ranges are transcribed VERBATIM from each PHP `Validator` rule chain:
 *   - SetRate:        amount 5..30
 *   - SetBill:        amount 20..200
 *   - SetSecretLimit: amount 1..99
 *   - SetNotice:      msg lengthMax 16384
 *   - SetScoutMsg:    msg lengthMax 1000
 *   - SetBlockWar / SetBlockScout: boolean value
 *
 * The permission gate is the shared [SecretPermission.financeSetterDenyReason] (every setter runs
 * the identical two lines). The setters write `rate`/`bill`/`secretlimit`/`war`/`scout` into
 * `nation.meta` (the opensamguk V1 schema folds these scalar PHP `nation` columns into the meta
 * jsonb — see `infra V1__baseline.sql` + `logic Nation.meta` doc), and `nationNotice`/`scout_msg`/
 * `available_war_setting_cnt` into the `nation_env` KV store.
 */
object NationFinanceSetters {

    // PHP Validator ranges (verbatim).
    const val RATE_MIN = 5
    const val RATE_MAX = 30
    const val BILL_MIN = 20
    const val BILL_MAX = 200
    const val SECRET_LIMIT_MIN = 1
    const val SECRET_LIMIT_MAX = 99
    const val NOTICE_MSG_MAX = 16384
    const val SCOUT_MSG_MAX = 1000

    /**
     * SetNotice.php — writes the `nationNotice` KV ({date,msg,author,authorID}) into `nation_env`.
     * `now`/`author`/`authorID` are supplied by the handler (TimeUtil::now / me.name / me.no).
     *
     * htmlPurify QUARANTINE: PHP runs `WebUtil::htmlPurify` (the HTMLPurifier HTML5 library). A
     * byte-faithful port is infeasible in this slice (external sanitizer). The faithful `if (!text)
     * return ''` guard IS reproduced; the raw text is stored otherwise. Flagged for P8.
     */
    fun setNotice(me: General, msg: String): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (msg.codePointCount(0, msg.length) > NOTICE_MSG_MAX) return FinanceSetterOutcome.Denied("'msg' 항목의 길이는 최대 ${NOTICE_MSG_MAX}자 입니다.")
        return FinanceSetterOutcome.Notice(htmlPurifyQuarantine(msg))
    }

    /** SetScoutMsg.php — writes `scout_msg` KV into `nation_env`. */
    fun setScoutMsg(me: General, msg: String): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (msg.codePointCount(0, msg.length) > SCOUT_MSG_MAX) return FinanceSetterOutcome.Denied("'msg' 항목의 길이는 최대 ${SCOUT_MSG_MAX}자 입니다.")
        return FinanceSetterOutcome.ScoutMsg(htmlPurifyQuarantine(msg))
    }

    /** SetRate.php — writes `nation.rate` (rides meta). */
    fun setRate(me: General, amount: Int): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (amount < RATE_MIN || amount > RATE_MAX) return FinanceSetterOutcome.Denied("'amount' 항목은 $RATE_MIN ~ $RATE_MAX 사이여야 합니다.")
        return FinanceSetterOutcome.NationMeta("rate", amount)
    }

    /** SetBill.php — writes `nation.bill` (rides meta). */
    fun setBill(me: General, amount: Int): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (amount < BILL_MIN || amount > BILL_MAX) return FinanceSetterOutcome.Denied("'amount' 항목은 $BILL_MIN ~ $BILL_MAX 사이여야 합니다.")
        return FinanceSetterOutcome.NationMeta("bill", amount)
    }

    /** SetSecretLimit.php — writes `nation.secretlimit` (rides meta). */
    fun setSecretLimit(me: General, amount: Int): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (amount < SECRET_LIMIT_MIN || amount > SECRET_LIMIT_MAX) {
            return FinanceSetterOutcome.Denied("'amount' 항목은 $SECRET_LIMIT_MIN ~ $SECRET_LIMIT_MAX 사이여야 합니다.")
        }
        return FinanceSetterOutcome.NationMeta("secretlimit", amount)
    }

    /**
     * SetBlockWar.php — writes `nation.war` (rides meta, 0/1) AND decrements the `nation_env`
     * `available_war_setting_cnt`. Denied (BEFORE any write) when the remaining count is ≤ 0.
     * `availableCnt` is read by the handler from the nation_env KV (default 0).
     */
    fun setBlockWar(me: General, value: Boolean, availableCnt: Int): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (availableCnt <= 0) return FinanceSetterOutcome.Denied("잔여 횟수가 부족합니다.")
        return FinanceSetterOutcome.BlockWar(value = if (value) 1 else 0, nextAvailableCnt = availableCnt - 1)
    }

    /**
     * SetBlockScout.php — writes `nation.scout` (rides meta, 0/1). Denied when the game-env
     * `block_change_scout` flag is set. `blockChangeScout` is read by the handler from game_env KV.
     */
    fun setBlockScout(me: General, value: Boolean, blockChangeScout: Boolean): FinanceSetterOutcome {
        SecretPermission.financeSetterDenyReason(me)?.let { return FinanceSetterOutcome.Denied(it) }
        if (blockChangeScout) return FinanceSetterOutcome.Denied("임관 설정을 바꿀 수 없도록 설정되어 있습니다.")
        return FinanceSetterOutcome.NationMeta("scout", if (value) 1 else 0)
    }

    /**
     * Faithful-only piece of htmlPurify: the `if (!text) return ''` falsy-guard (PHP `if (!$text)`).
     * The HTMLPurifier sanitisation itself is QUARANTINED (see [setNotice]). Returns '' for an empty
     * string (PHP falsy), the raw text otherwise.
     */
    internal fun htmlPurifyQuarantine(text: String): String = if (text.isEmpty()) "" else text
}

/** The resolved effect a finance setter produces (applied by the engine handler). */
sealed interface FinanceSetterOutcome {
    /** Permission/validation denied — the PHP-faithful reason string. */
    data class Denied(val reason: String) : FinanceSetterOutcome

    /** Write `nation.meta[key] = value` (rate/bill/secretlimit/scout). */
    data class NationMeta(val key: String, val value: Int) : FinanceSetterOutcome

    /** SetBlockWar: write `nation.meta[war]` AND set `nation_env.available_war_setting_cnt`. */
    data class BlockWar(val value: Int, val nextAvailableCnt: Int) : FinanceSetterOutcome

    /** SetNotice: write the `nationNotice` nation_env KV (handler builds the {date,msg,author,authorID} map). */
    data class Notice(val purifiedMsg: String) : FinanceSetterOutcome

    /** SetScoutMsg: write the `scout_msg` nation_env KV. */
    data class ScoutMsg(val purifiedMsg: String) : FinanceSetterOutcome
}
