package opensamguk.logic.domain

/**
 * A reserved nation-command row, keyed by `(nationId, officerLevel, turnIdx)` (V1 `nation_turn`).
 *
 * `action` = `action_code`; `arg` = the `arg` jsonb (null = empty); `brief` = the V2 `brief text`
 * column PHP writes on every nation_turn row (`che_거병.php:151` inserts `'brief'=>'휴식'` on all 24
 * rows — research OQ11). Modeled as a non-null String defaulting to `''` (FD0a adds the column).
 */
data class NationTurn(
    val nationId: Int,
    val officerLevel: Int,
    val turnIdx: Int,
    val action: String,
    val arg: Map<String, Any?>? = null,
    val brief: String = "",
)
