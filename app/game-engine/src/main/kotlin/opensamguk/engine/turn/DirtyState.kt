package opensamguk.engine.turn

import opensamguk.infra.persistence.KvWrite
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.inheritance.InheritanceResultRow
import java.time.Instant

/**
 * Composite key for a KV write — `(table, namespace, key)` (T0.3). `table == "nation_env"` is the
 * V3 int-namespace store (`namespace` = the nation id as a decimal string); any other `table`
 * (`game_env`/`betting`/`inheritance_{id}`/…) is the V7 string-namespace `game_kv` store. Keyed as a
 * data class so the recorder's dirty map dedups last-write-wins per logical key (KVStorage.php
 * semantics) while preserving insertion order in a LinkedHashMap.
 */
data class KvKey(val table: String, val namespace: String, val key: String)

/**
 * A diplomacy-row UPDATE patch (T0.4) keyed `(from, to)`. PHP `diplomacy` UPDATE
 * (`che_선전포고`/`수락`/`파기`/`종전`) toggles `state` + `term` (and may flip `dead`); this is the
 * per-command DELTA channel, distinct from the monthly TICK's bulk-SQL diplomacy update (P3
 * `PostUpdateMonthly`). Bidirectional transitions emit TWO patches (`(me,you)` + `(you,me)`).
 */
data class DiplomacyRowPatch(
    val fromNationId: Int,
    val toNationId: Int,
    val state: Int,
    val term: Int,
    val dead: Int? = null,
)

/**
 * A `message`-row INSERT INTENT (T0.5). The mailbox channel produces these (receiver row BEFORE
 * sender row — load-bearing); the flush INSERTs them append-additively. `id` is the in-memory
 * monotonic id the recorder pre-assigns (research §2: it MUST match the flushed SERIAL so the
 * `receiverMessageID`/`senderMessageID` back-references in the body resolve consistently). `bodyJson`
 * is the byte-faithful `Json::encode({src,dest,text,option})` payload.
 */
data class CreatedMessage(
    val id: Int,
    val mailbox: Int,
    val type: String,
    val srcId: Int,
    val destId: Int,
    val time: String,
    val validUntil: String,
    val bodyJson: String,
)

/**
 * A `message`-row invalidate UPDATE (T0.5, PHP `Message::invalidate`): rewrite the body jsonb +
 * `valid_until` for an existing message id (deleteMsg / accept-flow sibling-sweep).
 */
data class MessageInvalidate(
    val id: Int,
    val validUntil: String,
    val bodyJson: String,
)

/**
 * An `ng_auction` UPSERT intent (T0.7). `id` null → INSERT (open); non-null → UPDATE (extend/finish/
 * shrink). `columns` is the byte-faithful `AuctionInfo.toArray()` map. `allocatedId` carries the
 * pre-assigned in-memory id for an INSERT (so bids can reference it before flush).
 */
data class AuctionUpsert(val id: Int?, val allocatedId: Int?, val columns: Map<String, Any?>)

/**
 * An `ng_auction_bid` INSERT intent (T0.7). Outbid rows are NEVER deleted (research §3 — the refund is
 * a resource credit + Message, not a tombstone) — INSERT-only. `columns` is `AuctionBidItem.toArray()`.
 */
data class AuctionBidInsert(val columns: Map<String, Any?>)

/**
 * An `ng_betting` INSERT intent (P6 betting intake). INSERT-only — bet rows are never updated.
 * `columns` mirrors `NgBettingEntity` fields: betting_id, general_id, user_id, betting_type, amount.
 */
data class BettingInsert(val columns: Map<String, Any?>)

/**
 * `board_post` INSERT 의도 (F4 Wave C2 슬라이스 C, 회의실/기밀실 글). INSERT 전용 — 글은 절대 갱신되지
 * 않는다. `columns`는 board_post 컬럼을 미러링: nation_id, is_secret, author_general_id, author_name,
 * title, content_html.
 */
data class BoardPostInsert(val columns: Map<String, Any?>)

/**
 * `board_comment` INSERT 의도 (F4 Wave C2 슬라이스 C, 회의실/기밀실 댓글). INSERT 전용. `columns`는
 * board_comment 컬럼을 미러링: post_id, nation_id, is_secret, author_general_id, author_name,
 * content_text.
 */
data class BoardCommentInsert(val columns: Map<String, Any?>)

/**
 * Snapshot of a removed nation, captured for the per-season `ng_old_nations` archive
 * write (mirrors `inMemoryWorld.ts` `deletedNationSnapshots`).
 */
data class DeletedNationSnapshot(
    val nation: Nation,
    val generalIds: List<Int>,
    val removedAt: Instant,
)

/**
 * Exact return shape of `InMemoryTurnWorld.consumeDirtyState()`
 * (faithful to `inMemoryWorld.ts:697-775`). Single-shot: produced once per flush,
 * then all source sets are cleared.
 */
data class DirtyState(
    val generals: List<TurnGeneral>,
    val cities: List<City>,
    val nations: List<Nation>,
    val troops: List<Troop>,
    val deletedTroops: List<Int>,
    val deletedGenerals: List<Int>,
    val deletedNations: List<Int>,
    val deletedNationSnapshots: List<DeletedNationSnapshot>,
    val diplomacy: List<TurnDiplomacy>,
    val logs: List<LogEntryDraft>,
    val createdGenerals: List<TurnGeneral>,
    val createdNations: List<Nation>,
    val createdTroops: List<Troop>,
    val createdDiplomacy: List<TurnDiplomacy>,
    /**
     * P2 satellite write-set (Task FF1):
     *  - [rankDirty]: per-general rank_data deltas — at most one [RankDelta] per [RankColumn]
     *    (the 3-Map collapse). Flushed in step-8 (rankVarIncrease then rankVarSet).
     *  - [nationTurnDirty]: reserved nation-command rows to (re)write (step-3 createMany / step-7).
     *  - [kvDirty]: `(table, namespace, key)` → json | `null`-deletes (step-10; delete-on-null,
     *    KVStorage.php). Keyed by [KvKey] so the int-ns `nation_env` AND the string-ns
     *    `game_env`/`betting`/`inheritance_{id}` writes share one channel (T0.3).
     */
    val rankDirty: Map<Int, Map<RankColumn, RankDelta>> = emptyMap(),
    val nationTurnDirty: List<NationTurn> = emptyList(),
    val kvDirty: Map<KvKey, Any?> = emptyMap(),
    /**
     * [diplomacyUpdateDirty]: per-command diplomacy-row UPDATE patches keyed `(from, to)` (T0.4).
     * The DELTA channel for 선전포고/수락/파기/종전 — distinct from the monthly TICK's bulk-SQL
     * diplomacy update; the two must not collide (flush-order: commands during the pass, tick AFTER).
     */
    val diplomacyUpdateDirty: List<DiplomacyRowPatch> = emptyList(),
    /** [createdMessages]: the mailbox-channel INSERT intents (receiver-before-sender, append-additive). */
    val createdMessages: List<CreatedMessage> = emptyList(),
    /** [messageInvalidates]: the mailbox-channel invalidate UPDATEs (deleteMsg / sibling-sweep). */
    val messageInvalidates: List<MessageInvalidate> = emptyList(),
    /** [auctionUpserts]: the ng_auction INSERT/UPDATE intents (T0.7). */
    val auctionUpserts: List<AuctionUpsert> = emptyList(),
    /** [auctionBidInserts]: the ng_auction_bid INSERT intents (T0.7, INSERT-only — no outbid delete). */
    val auctionBidInserts: List<AuctionBidInsert> = emptyList(),
    /** [bettingInserts]: the ng_betting INSERT intents (P6 betting intake, INSERT-only). */
    val bettingInserts: List<BettingInsert> = emptyList(),
    /** [inheritanceKvWrites]: the inheritance-channel KV writes (T0.8). */
    val inheritanceKvWrites: List<KvWrite> = emptyList(),
    /** [inheritanceLogInserts]: the inheritance_log INSERT intents (T0.8). */
    val inheritanceLogInserts: List<InheritanceLogDraft> = emptyList(),
    /** [inheritanceResultInserts]: the inheritance_result INSERT intents (T0.8). */
    val inheritanceResultInserts: List<InheritanceResultRow> = emptyList(),
)
