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
 * A `diplomacy_letter` INSERT intent (W5d 외교 서신 발송). INSERT 전용. `allocatedId`는 recorder가
 * 선할당한 in-memory id(=PHP `insertId()` = newLetterNo)로, 같은 tick의 메시지/결과가 flush 전에
 * letterNo를 참조한다(in-memory 단조 id가 flushed SERIAL과 일치 — auction open INSERT 패턴). `columns`는
 * byte-faithful diplomacy_letter 컬럼 맵.
 */
data class DiplomacyLetterInsert(val allocatedId: Int, val columns: Map<String, Any?>)

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
 * An `ng_betting` write intent (P6 betting intake). `columns` mirrors `NgBettingEntity` fields:
 * betting_id, general_id, user_id, betting_type, amount. W0-8: flush 측은 PHP `insertUpdate`
 * 패러티의 UPSERT — UNIQUE(general_id,betting_id,betting_type) 충돌(동일 키 재베팅) 시 amount 누적.
 */
data class BettingInsert(val columns: Map<String, Any?>)

/**
 * OPENSAM-150 (R1) — `v2_city_ledger` UPSERT 의도 (v2 도시 원장 채널, betting 채널과 동일 패턴).
 * `columns`는 `city_id`/`gold`/`rice`/`garrison`을 미러링하며 **절대값**(누적 델타가 아니다) —
 * flush는 `(world_id, city_id)` 충돌 시 세 값을 덮어쓰는 **멱등 UPSERT**라 재시작 재실행이 안전하다.
 * 이 컬렉션이 비면 `DatabaseHooks`가 빈 리스트를 싣고 v2 flush step이 미진입한다 ⇒ v1 경로 SQL 0.
 */
data class CityLedgerV2Upsert(val columns: Map<String, Any?>)

/** OPENSAM-94 프로필 아이콘 sync — general portrait 컬럼(picture/image_server) UPDATE 의도. */
data class ProfileIconUpdate(val columns: Map<String, Any?>)

/**
 * `board_post` INSERT 의도 (F4 Wave C2 슬라이스 C, 회의실/기밀실 글). INSERT 전용 — 글은 절대 갱신되지
 * 않는다. `columns`는 board_post 컬럼을 미러링: nation_id, is_secret, author_general_id, author_name,
 * author_icon(W0-8 V15 — NULL 허용, PHP board.author_icon), title, content_html.
 */
data class BoardPostInsert(val columns: Map<String, Any?>)

/**
 * `board_comment` INSERT 의도 (F4 Wave C2 슬라이스 C, 회의실/기밀실 댓글). INSERT 전용. `columns`는
 * board_comment 컬럼을 미러링: post_id, nation_id, is_secret, author_general_id, author_name,
 * content_text.
 */
data class BoardCommentInsert(val columns: Map<String, Any?>)

/** `battle_replay` INSERT 의도(Phase 4X-C, 계획이 봉인된 전투만). INSERT 전용. `id` 는 recorder 선할당(DB-seed). */
data class BattleReplayInsert(val columns: Map<String, Any?>)

/**
 * `board_post_read` INSERT 의도 (ADR-LITE-049 14 기밀실 열람 기록). INSERT 전용·멱등 — `columns`는
 * post_id, general_id. 같은 (post_id, general_id) 는 DB UNIQUE 가 무시한다(ON CONFLICT DO NOTHING).
 */
data class BoardReadInsert(val columns: Map<String, Any?>)

/**
 * `vote_poll` INSERT 의도 (F4 Wave 투표, 설문조사 개설 NewVote.php). INSERT 전용. `columns`는 vote_poll
 * 컬럼을 미러링: title, body, options(jsonb), multiple_options, reveal_mode, opener_general_id,
 * opener_name, start_at, end_at.
 */
data class VotePollInsert(val columns: Map<String, Any?>)

/**
 * `vote` INSERT 의도 (F4 Wave 투표, 투표 던지기 Vote.php). INSERT 전용 (PHP `insertIgnore` →
 * UNIQUE(vote_id,general_id) 중복은 DB가 무시). `columns`는 vote 컬럼을 미러링: vote_id, general_id,
 * nation_id, selection(jsonb).
 */
data class VoteInsert(val columns: Map<String, Any?>)

/**
 * `vote_comment` INSERT 의도 (F4 Wave 투표, 댓글 AddComment.php). INSERT 전용. `columns`는 vote_comment
 * 컬럼을 미러링: vote_id, general_id, nation_id, general_name, nation_name, text.
 */
data class VoteCommentInsert(val columns: Map<String, Any?>)

/**
 * `statistic` INSERT 의도 (W1 checkStatistic). INSERT 전용 — 연감 row를 1년 경계에 추가.
 * `columns`는 statistic 테이블 컬럼을 미러링: year, month, nation_count, nation_name, nation_hist,
 * gen_count, personal_hist, special_hist, power_hist, crewtype, etc, aux(jsonb).
 */
data class StatisticInsert(val columns: Map<String, Any?>)

/**
 * `yearbook_history` UPSERT 의도 (W0-8 연감 채널 — P0-20 LogHistory 월별 스냅샷, func_history.php:436-448).
 * `columns`는 yearbook_history 컬럼을 미러링: profile_name, year, month, map(jsonb), nations(jsonb),
 * global_history(jsonb), global_action(jsonb), hash. (profile_name,year,month) UNIQUE 충돌 시 갱신
 * (재기동-재실행 멱등). 기록 주체는 W1-I의 LogHistory writer — 이 채널은 W0-8이 선공급한 운반체.
 */
data class YearbookInsert(val columns: Map<String, Any?>)

data class GameWinnerUpdate(val serverId: String, val winnerNation: Int)

data class EmperiorInsert(val columns: Map<String, Any?>)

data class HallUpsert(val columns: Map<String, Any?>)

/**
 * Snapshot of a removed nation, captured for the per-season `ng_old_nations` archive
 * write (mirrors `inMemoryWorld.ts` `deletedNationSnapshots`).
 */
data class DeletedNationSnapshot(
    val nation: Nation,
    val generalIds: List<Int>,
    val removedAt: Instant,
    val serverId: String? = null,
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
    /** Phase 4X-A 가신·부곡 — troops 와 같은 world-lifecycle 채널(step-8g, 표마다 DELETE → CREATE → UPDATE). */
    val retainers: List<Retainer> = emptyList(),
    val createdRetainers: List<Retainer> = emptyList(),
    val deletedRetainers: List<Int> = emptyList(),
    val bugoks: List<Bugok> = emptyList(),
    val createdBugoks: List<Bugok> = emptyList(),
    val deletedBugoks: List<Int> = emptyList(),
    /** Phase 4X-B 작전 — step-8h(8g 뒤), 표마다 DELETE → CREATE → UPDATE. */
    val operations: List<Operation> = emptyList(),
    val createdOperations: List<Operation> = emptyList(),
    val deletedOperations: List<Int> = emptyList(),
    val operationUnits: List<OperationUnit> = emptyList(),
    val createdOperationUnits: List<OperationUnit> = emptyList(),
    val deletedOperationUnits: List<Int> = emptyList(),
    /** Phase 4X-C 출병 계획 — step-8i(8h 뒤), DELETE → CREATE → UPDATE. 리플레이는 recorder INSERT 채널. */
    val battlePlans: List<BattlePlan> = emptyList(),
    val createdBattlePlans: List<BattlePlan> = emptyList(),
    val deletedBattlePlans: List<Int> = emptyList(),
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
    /** [diplomacyLetterInserts]: the W5d 외교 서신 INSERT intents (발송, append-additive, INSERT-only). */
    val diplomacyLetterInserts: List<DiplomacyLetterInsert> = emptyList(),
    /**
     * [diplomacyLetterUpdates]: the W5d 외교 서신 UPDATE 채널 — letterNo → 변경 컬럼 맵. send의 prev→replaced,
     * rollback→cancelled, destroy의 state_opt/cancelled 전환에 대응하는 DELTA(INSERT 채널과 별개 행 UPDATE).
     * 키별 LinkedHashMap, 컬럼별 last-write-wins, 삽입 순서 보존(votePollUpdates/diplomacyUpdateDirty 형태).
     */
    val diplomacyLetterUpdates: Map<Int, Map<String, Any?>> = emptyMap(),
    /**
     * [votePollUpdates]: per-tick vote_poll UPDATE 채널 (F4 Wave 투표) — pollId → 변경 컬럼 맵. 새 설문조사
     * 개설 시 이전 설문을 닫는 `NewVote.closeOldVote`(endDate=now) + 자연 만료(closed_at)에 대응하는 DELTA
     * 채널. INSERT 전용 vote_poll INSERT 채널과 별개(같은 테이블이지만 다른 행: 신규 INSERT vs 기존 UPDATE).
     * 키별 LinkedHashMap, 컬럼별 last-write-wins, 삽입 순서 보존(diplomacyUpdateDirty와 동일 형태).
     */
    val votePollUpdates: Map<Int, Map<String, Any?>> = emptyMap(),
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
    /** [statisticInserts]: the `statistic` INSERT intents (W1 checkStatistic). INSERT-only. */
    val statisticInserts: List<StatisticInsert> = emptyList(),
)
