package opensamguk.logic.domain

/**
 * Directional-pair diplomacy logic entity.
 *
 * PHP/V1 stores diplomacy as a directional `(src_nation_id, dest_nation_id)` row with a `state_code`
 * and a `term` (remaining months). `me` = src_nation_id, `you` = dest_nation_id. State semantics
 * (교전/불가침/통상 …) are ported by the dest-* constraint family (C-DEST); FD0 only fixes the shape.
 */
data class Diplomacy(
    val me: Int,
    val you: Int,
    var state: Int,
    var term: Int = 0,
    var dead: Int = 0,
)
