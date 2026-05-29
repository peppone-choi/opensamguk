package opensamguk.engine.flush

/**
 * Frozen per-season reset allow/deny contract, transcribed from the PHP grand truth
 * `legacy/devsam-core/hwe/sammo/ResetHelper.php` (`clearDB()`) + `hwe/sql/reset.sql`,
 * mapped onto the V1 baseline (`infra/src/main/resources/db/migration/V1__baseline.sql`)
 * table names.
 *
 * This contract sits OUTSIDE the world+flush boundary (design §7): the InMemoryTurnWorld /
 * DatabaseHooks flush path never touches these tables. It exists so a future per-season reset
 * NEVER truncates the cross-season survivor set.
 *
 * Survivors (PHP intent):
 *  - `hall`                      — 명전 ("명전 테이블은 삭제하지 않음")
 *  - `ng_old_nations` /
 *    `ng_old_generals`           — 왕조/dynasty archive ("왕조 테이블은 삭제하지 않음")
 *  - `ng_games`                  — cross-season game registry (queried for season/serverCnt)
 *  - `inheritance_*`             — storage namespaces other than `general_%` survive reset
 *  - `error_log`                 — operational logging, not per-season game state
 *
 * Truncated (per-season game tables; DROP/TRUNCATEd by reset.sql or re-seeded by the scenario
 * builder): world_state, nation, city, general, troop, general_turn, nation_turn, diplomacy,
 * diplomacy_letter, rank_data, yearbook_history (연감 — world_history is dropped in reset.sql),
 * event, log_entry, auction, auction_bid, board_post, board_comment, vote_poll, vote, vote_comment.
 */
object TruncateContract {
    val SURVIVE: Set<String> = setOf(
        "hall",
        "ng_games",
        "ng_old_nations",
        "ng_old_generals",
        "error_log",
        "inheritance_point",
        "inheritance_log",
        "inheritance_result",
        "inheritance_user_state",
    )

    val TRUNCATED: Set<String> = setOf(
        "world_state",
        "nation",
        "city",
        "general",
        "troop",
        "general_turn",
        "nation_turn",
        "diplomacy",
        "diplomacy_letter",
        "rank_data",
        "yearbook_history",
        "event",
        "log_entry",
        "auction",
        "auction_bid",
        "board_post",
        "board_comment",
        "vote_poll",
        "vote",
        "vote_comment",
    )

    fun isExcludedFromTruncate(table: String): Boolean = table in SURVIVE
}
