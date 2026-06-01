package opensamguk.engine.turn

import java.time.Instant
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

/**
 * Column patch for ONE dirty row — only the columns that actually changed. `meta` deep-changes are
 * carried separately (only the changed/added meta keys, in the post-state's insertion order so the
 * jsonb the flush writes is byte-comparable to the PHP golden's key order).
 *
 * `columns` excludes `meta` (the jsonb column) — that change is described by [meta]. A patch with
 * empty `columns` but non-empty `meta` is still a real change (a meta-only mutation, e.g. the
 * success branch bumping `max_domestic_critical`).
 */
data class RowPatch(
    val id: Int,
    val columns: Map<String, Any?>,
    val meta: Map<String, Any?>,
)

/**
 * F2 — the Immer-`produceWithPatches` replacement and the **single dirty source**.
 *
 * The resolver mutates a [opensamguk.logic.actions.GeneralActionDraft] (immutable `copy()`
 * replacement) and NEVER calls the world's `updateGeneral`/`updateCity`. The handler (F3) diffs the
 * pre-state vs post-state logic [General]/[City] through this recorder; the resulting [RowPatch]es
 * are the ONLY thing that marks a row dirty. Having two dirty sources (the resolver AND the world)
 * would silently diverge the flush (design Risk #4), so this is deliberately the lone path.
 *
 * Faithful to the TS `produceWithPatches(draft, recipe)` shape used in the daemon turn loop: a
 * no-op recipe yields no patch (and nothing dirty); a real mutation yields exactly the changed
 * paths. We model the JSON-Patch "path" coarsely as the column name (the flush maps column → SQL),
 * with `meta` deep-diffed at the key level (the jsonb sub-paths).
 */
class ChangeRecorder(
    /**
     * Allocates the next in-memory `message.id` (T0.5). The daemon wires a DB-seeded monotonic
     * allocator (max(id)+1 at rehydrate) so the in-memory id matches the flushed SERIAL (research §2:
     * the body's `receiverMessageID`/`senderMessageID` back-references resolve against this id). The
     * default 1-based counter is for tests / a fresh world.
     */
    private val messageIdAllocator: () -> Int = AtomicCounter()::next,
    /** Allocates the next in-memory `ng_auction.id` (T0.7) — DB-seeded at rehydrate; default 1-based. */
    private val auctionIdAllocator: () -> Int = AtomicCounter()::next,
) {

    private val generalPatches = LinkedHashMap<Int, RowPatch>()
    private val cityPatches = LinkedHashMap<Int, RowPatch>()
    private val nationPatches = LinkedHashMap<Int, RowPatch>()

    /**
     * Per-general rank_data deltas — the 3-Map collapse. At most ONE [RankDelta] per `(general,
     * type)` survives (faithful to `General.php` increaseRankVar/setRankVar: a Set displaces a
     * pending Increment; an Increment over an existing Set folds into the Set; two Increments add).
     */
    private val rankPatches = LinkedHashMap<Int, LinkedHashMap<RankColumn, RankDelta>>()

    /**
     * Tombstoned general/nation ids (the kill/destroy DELETE seam, `General.php:515-600`). A row in
     * here is permanently excluded from the update-set: [diffGeneral]/[diffNation] short-circuit for
     * a tombstoned id (kill() clears updatedVar at `General.php:595` so a trailing applyDB never
     * re-INSERTs the dead row). These mirror the world's deleted sets — they exist on the recorder so
     * the recorder stays the SINGLE dirty source (no family calls `world.removeX` directly).
     */
    private val deletedGeneralIds = LinkedHashSet<Int>()
    private val deletedNationIds = LinkedHashSet<Int>()

    /**
     * KV delta channel (T0.3) — `(table, namespace, key)` → encoded value | `null`-delete. The recorder
     * is the SOLE emitter (no family writes a KV table inline). Last-write-wins per [KvKey] (KVStorage.php
     * delete-on-null + last-write-wins); insertion order preserved (LinkedHashMap, never re-keyed).
     * Feeds [DirtyState.kvDirty] → `FlushPayload.kvWrites` → the executor's step-10 (nation_env int-ns
     * AND game_kv string-ns). Without this channel every nation/kv delta ran in memory and vanished at
     * flush.
     */
    private val kvDirty = LinkedHashMap<KvKey, Any?>()

    /**
     * Diplomacy UPDATE delta channel (T0.4) — `(from, to)` → [DiplomacyRowPatch]. The recorder is the
     * SOLE per-command emitter. Last-write-wins per `(from, to)` (a later transition in the same tick
     * displaces the earlier patch); insertion order preserved. This is DISTINCT from the monthly TICK's
     * bulk-SQL diplomacy update (P3 `PostUpdateMonthly`) — they run at different points in the pass and
     * must not corrupt each other (commands during the general/nation pass, tick AFTER).
     */
    private val diplomacyUpdateDirty = LinkedHashMap<Pair<Int, Int>, DiplomacyRowPatch>()

    /**
     * Mailbox channel (T0.5) — the `message` INSERT intents, append-additive (receiver row BEFORE
     * sender row; the caller emits them in that order). NEVER re-keyed/dedup'd: every `send` row is a
     * distinct mailbox row.
     */
    private val createdMessages = mutableListOf<CreatedMessage>()

    /** Mailbox channel (T0.5) — the `message` invalidate UPDATEs (deleteMsg / accept-flow sibling-sweep). */
    private val messageInvalidates = mutableListOf<MessageInvalidate>()

    /** Auction channel (T0.7) — ng_auction UPSERTs (open INSERT / extend-finish UPDATE), in emit order. */
    private val auctionUpserts = mutableListOf<AuctionUpsert>()

    /** Auction channel (T0.7) — ng_auction_bid INSERTs (INSERT-only; outbid rows NEVER deleted). */
    private val auctionBidInserts = mutableListOf<AuctionBidInsert>()

    /** storeOldGeneral content — the pre-delete general rows (`ng_old_generals` archive, `func_gamerule.php:668`). */
    private val oldGeneralSnapshots = mutableListOf<TurnGeneral>()

    /** Pre-delete nation snapshots (the `ng_old_nations` archive write — `DatabaseHooks` step-2). */
    private val nationSnapshots = mutableListOf<DeletedNationSnapshot>()

    val isDirty: Boolean
        get() = generalPatches.isNotEmpty() || cityPatches.isNotEmpty() ||
            nationPatches.isNotEmpty() || rankPatches.isNotEmpty() ||
            deletedGeneralIds.isNotEmpty() || deletedNationIds.isNotEmpty() ||
            kvDirty.isNotEmpty() || diplomacyUpdateDirty.isNotEmpty() ||
            createdMessages.isNotEmpty() || messageInvalidates.isNotEmpty() ||
            auctionUpserts.isNotEmpty() || auctionBidInserts.isNotEmpty()

    fun dirtyGeneralIds(): Set<Int> = generalPatches.keys.toSet()
    fun dirtyCityIds(): Set<Int> = cityPatches.keys.toSet()
    fun dirtyNationIds(): Set<Int> = nationPatches.keys.toSet()

    /** The tombstoned general/nation ids (the kill/destroy DELETE seam). */
    fun deletedGeneralIds(): Set<Int> = deletedGeneralIds.toSet()
    fun deletedNationIds(): Set<Int> = deletedNationIds.toSet()

    /** The captured pre-delete general rows (storeOldGeneral content). */
    fun oldGeneralSnapshots(): List<TurnGeneral> = oldGeneralSnapshots.toList()

    /** The captured pre-delete nation snapshots (the `ng_old_nations` archive write). */
    fun nationSnapshots(): List<DeletedNationSnapshot> = nationSnapshots.toList()
    fun generalPatches(): List<RowPatch> = generalPatches.values.toList()
    fun cityPatches(): List<RowPatch> = cityPatches.values.toList()
    fun nationPatches(): List<RowPatch> = nationPatches.values.toList()

    /** All recorded rank deltas, per general (the FF2 flush step-8 source). */
    fun rankPatches(): Map<Int, Map<RankColumn, RankDelta>> =
        rankPatches.mapValues { (_, m) -> m.toMap() }

    /** The collapsed rank deltas for one general (empty when none recorded). */
    fun rankDeltas(generalId: Int): Map<RankColumn, RankDelta> =
        rankPatches[generalId]?.toMap() ?: emptyMap()

    /**
     * Diff a general's pre/post draft. Returns the [RowPatch] (and records it as dirty) if anything
     * changed, or `null` if `pre == post` (no-op recipe → not dirty). The `id` is taken from `post`.
     */
    fun diffGeneral(pre: LogicGeneral, post: LogicGeneral): RowPatch? {
        require(pre.id == post.id) { "ChangeRecorder.diffGeneral: id changed (${pre.id} -> ${post.id})" }
        // A tombstoned general never re-enters the update-set (kill() clears updatedVar, General.php:595).
        if (post.id in deletedGeneralIds) return null
        val columns = LinkedHashMap<String, Any?>()
        // slice-relevant scalar columns the che resolver can touch (PHP increaseVar/setVar targets).
        diffCol(columns, "gold", pre.gold, post.gold)
        diffCol(columns, "experience", pre.experience, post.experience)
        diffCol(columns, "dedication", pre.dedication, post.dedication)
        diffCol(columns, "officerLevel", pre.officerLevel, post.officerLevel)
        diffCol(columns, "rice", pre.rice, post.rice)
        diffCol(columns, "injury", pre.injury, post.injury)
        diffCol(columns, "cityId", pre.cityId, post.cityId)
        diffCol(columns, "nationId", pre.nationId, post.nationId)
        diffCol(columns, "leadership", pre.leadership, post.leadership)
        diffCol(columns, "strength", pre.strength, post.strength)
        diffCol(columns, "intel", pre.intel, post.intel)
        // P2 military / equip surface (Task FF1).
        diffCol(columns, "crew", pre.crew, post.crew)
        diffCol(columns, "train", pre.train, post.train)
        diffCol(columns, "atmos", pre.atmos, post.atmos)
        diffCol(columns, "crewTypeId", pre.crewTypeId, post.crewTypeId)
        diffCol(columns, "troop", pre.troop, post.troop)
        diffCol(columns, "horse", pre.horse, post.horse)
        diffCol(columns, "weapon", pre.weapon, post.weapon)
        diffCol(columns, "book", pre.book, post.book)
        diffCol(columns, "item", pre.item, post.item)
        diffCol(columns, "npcType", pre.npcType, post.npcType)
        // P4 conquest surface (Task FU3): ConquerCity demotes governors to 재야 (officer_city=0,
        // officer_level=1) — process_war.php:705-708. Generals SURVIVE → no markGeneralDeleted.
        diffCol(columns, "officerCity", pre.officerCity, post.officerCity)
        // last_turn (general-command setResultTurn target) — delete-on-default jsonb.
        diffCol(columns, "lastTurn", pre.lastTurn, post.lastTurn)

        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        generalPatches[post.id] = patch
        return patch
    }

    /**
     * Diff a city's pre/post draft. Returns the [RowPatch] (and records it dirty) if anything
     * changed, or `null` if `pre == post`.
     */
    fun diffCity(pre: LogicCity, post: LogicCity): RowPatch? {
        require(pre.id == post.id) { "ChangeRecorder.diffCity: id changed (${pre.id} -> ${post.id})" }
        val columns = LinkedHashMap<String, Any?>()
        diffCol(columns, "commerce", pre.commerce, post.commerce)
        diffCol(columns, "agriculture", pre.agriculture, post.agriculture)
        diffCol(columns, "commerceMax", pre.commerceMax, post.commerceMax)
        diffCol(columns, "agricultureMax", pre.agricultureMax, post.agricultureMax)
        diffCol(columns, "supplyState", pre.supplyState, post.supplyState)
        diffCol(columns, "frontState", pre.frontState, post.frontState)
        diffCol(columns, "trust", pre.trust, post.trust)
        diffCol(columns, "level", pre.level, post.level)
        diffCol(columns, "nationId", pre.nationId, post.nationId)
        // P2 develop/defense surface (Task FF1): secu/def/wall/pop + each _max, trade, region.
        diffCol(columns, "security", pre.security, post.security)
        diffCol(columns, "securityMax", pre.securityMax, post.securityMax)
        diffCol(columns, "defense", pre.defense, post.defense)
        diffCol(columns, "defenseMax", pre.defenseMax, post.defenseMax)
        diffCol(columns, "wall", pre.wall, post.wall)
        diffCol(columns, "wallMax", pre.wallMax, post.wallMax)
        diffCol(columns, "population", pre.population, post.population)
        diffCol(columns, "populationMax", pre.populationMax, post.populationMax)
        diffCol(columns, "trade", pre.trade, post.trade)
        diffCol(columns, "region", pre.region, post.region)
        // P4 war/conquest surface (Task FU3). `front` is already covered by frontState→front_state
        // above (PHP city.front == opensamguk front_state); term/officer_set/conflict are NEW.
        diffCol(columns, "term", pre.term, post.term)
        diffCol(columns, "officerSet", pre.officerSet, post.officerSet)
        diffCol(columns, "conflict", pre.conflict, post.conflict)

        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        cityPatches[post.id] = patch
        return patch
    }

    /**
     * Diff a nation's pre/post draft (Task FF1). Returns the [RowPatch] (and records it dirty) if
     * gold/rice/capital/name/color/tech/level changed, or `meta` deep-changed (gennum/capset/rate/
     * bill/aux …). Returns `null` if `pre == post`. The scalar columns map to the V1 `nation` row
     * column names (`capital_city_id`, `type_code`); `gennum`/`capset` ride the `meta` jsonb and are
     * carried in [RowPatch.meta] (a 증축/감축/천도 bumps `capset`, invalidating term-stacks).
     */
    fun diffNation(pre: LogicNation, post: LogicNation): RowPatch? {
        require(pre.id == post.id) { "ChangeRecorder.diffNation: id changed (${pre.id} -> ${post.id})" }
        // A tombstoned nation never re-enters the update-set (the cascade delete is the final word).
        if (post.id in deletedNationIds) return null
        val columns = LinkedHashMap<String, Any?>()
        diffCol(columns, "name", pre.name, post.name)
        diffCol(columns, "color", pre.color, post.color)
        diffCol(columns, "capital_city_id", pre.capitalCityId, post.capitalCityId)
        diffCol(columns, "gold", pre.gold, post.gold)
        diffCol(columns, "rice", pre.rice, post.rice)
        diffCol(columns, "tech", pre.tech, post.tech)
        diffCol(columns, "level", pre.level, post.level)
        diffCol(columns, "type_code", pre.typeCode, post.typeCode)

        // capset/gennum ride meta; diffMeta walks the post-state in insertion order so the jsonb
        // the flush writes preserves PHP `Json::encode` key order.
        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        nationPatches[post.id] = patch
        return patch
    }

    /**
     * Record a rank_data `value = value + [value]` (`increaseRankVar`, `General.php:641-660`):
     *  - an existing `Set` folds the increase into the Set,
     *  - an existing `Increment` accumulates,
     *  - otherwise a fresh `Increment`.
     */
    fun recordRankIncrease(generalId: Int, column: RankColumn, value: Int) {
        val map = rankPatches.getOrPut(generalId) { LinkedHashMap() }
        map[column] = when (val existing = map[column]) {
            is RankDelta.Set -> RankDelta.Set(existing.value + value)
            is RankDelta.Increment -> RankDelta.Increment(existing.value + value)
            null -> RankDelta.Increment(value)
        }
    }

    /**
     * Record a rank_data `value = [value]` (`setRankVar`, `General.php:662-670`): a Set displaces
     * any pending Increment/Set for that `(general, type)` — at most one delta survives.
     */
    fun recordRankSet(generalId: Int, column: RankColumn, value: Int) {
        val map = rankPatches.getOrPut(generalId) { LinkedHashMap() }
        map[column] = RankDelta.Set(value)
    }

    /**
     * Record a KV write (T0.3, `KVStorage.php` setValue): `value == null` is a delete-on-null; any
     * other value is the (already-encoded jsonb String, or to-be-encoded Int/Map/List) payload. Last
     * write wins per `(table, namespace, key)` (a later set/delete displaces the earlier one), matching
     * the PHP KV last-write-wins + delete semantics; insertion order is preserved so the flush emits
     * the writes in the order the resolver produced them.
     *
     *  - `table == "nation_env"` → V3 int-namespace store (`namespace` = nation id as a decimal string).
     *  - any other `table` (`game_env`/`betting`/`inheritance_{id}`) → V7 `game_kv` string-namespace store.
     */
    fun recordKv(table: String, namespace: String, key: String, value: Any?) {
        kvDirty[KvKey(table, namespace, key)] = value
    }

    /** Convenience for the V3 int-namespace `nation_env` writes (setNationMeta, term-stacks). */
    fun recordNationEnvKv(nationId: Int, key: String, value: Any?) {
        recordKv("nation_env", nationId.toString(), key, value)
    }

    /** The recorded KV delta channel (the T0.3 step-10 source), insertion-ordered. */
    fun kvDirty(): Map<KvKey, Any?> = LinkedHashMap(kvDirty)

    /**
     * Diff a diplomacy row's pre/post (T0.4). Returns the [DiplomacyRowPatch] (and records it dirty)
     * if `state`/`term`/`dead` changed for `(from, to)`, or `null` if nothing changed (no-op → not
     * dirty). The `(from, to)` key is taken from `post`. A bidirectional transition (선전포고/수락/…)
     * calls this TWICE — once per direction — so BOTH `(me,you)` + `(you,me)` rows land (PHP updates
     * both via `(me=A AND you=B) OR (me=B AND you=A)`; missing one desyncs the matrix + the next tick).
     */
    fun diffDiplomacy(pre: TurnDiplomacy, post: TurnDiplomacy): DiplomacyRowPatch? {
        require(pre.fromNationId == post.fromNationId && pre.toNationId == post.toNationId) {
            "ChangeRecorder.diffDiplomacy: key changed (${pre.fromNationId},${pre.toNationId}) -> (${post.fromNationId},${post.toNationId})"
        }
        if (pre.state == post.state && pre.term == post.term && pre.dead == post.dead) return null
        val patch = DiplomacyRowPatch(
            fromNationId = post.fromNationId,
            toNationId = post.toNationId,
            state = post.state,
            term = post.term,
            dead = if (pre.dead != post.dead) post.dead else null,
        )
        diplomacyUpdateDirty[post.fromNationId to post.toNationId] = patch
        return patch
    }

    /** The recorded per-command diplomacy UPDATE patches (the T0.4 step-7 source), insertion-ordered. */
    fun diplomacyUpdateDirty(): List<DiplomacyRowPatch> = diplomacyUpdateDirty.values.toList()

    /**
     * Record a `message` INSERT (T0.5, PHP `sendRaw`). Pre-allocates the in-memory id (so the body's
     * `receiverMessageID`/`senderMessageID` back-references can be folded in by the caller before the
     * `bodyJson` is built) and appends — receiver row BEFORE sender row is the CALLER's responsibility
     * (it emits them in that order). Returns the allocated id. Append-additive: never deduped.
     */
    fun recordMessageInsert(
        mailbox: Int,
        type: String,
        srcId: Int,
        destId: Int,
        time: String,
        validUntil: String,
        bodyJson: String,
    ): Int {
        val id = messageIdAllocator()
        createdMessages.add(CreatedMessage(id, mailbox, type, srcId, destId, time, validUntil, bodyJson))
        return id
    }

    /** Record a `message` invalidate UPDATE (T0.5, PHP `Message::invalidate`): rewrite body + valid_until. */
    fun recordMessageInvalidate(id: Int, validUntil: String, bodyJson: String) {
        messageInvalidates.add(MessageInvalidate(id, validUntil, bodyJson))
    }

    /** The recorded mailbox INSERT intents (the T0.5 flush source), in emit order (receiver-before-sender). */
    fun createdMessages(): List<CreatedMessage> = createdMessages.toList()

    /** The recorded mailbox invalidate UPDATEs (the T0.5 flush source). */
    fun messageInvalidates(): List<MessageInvalidate> = messageInvalidates.toList()

    /**
     * Record an `ng_auction` UPSERT (T0.7). `id` null → an INSERT (auction open): pre-allocates the
     * in-memory id and returns it (so bids placed in the same tick can reference it before flush). A
     * non-null `id` → an UPDATE (extend/finish/shrink). `columns` is the byte-faithful
     * `AuctionInfo.toArray()` map (the caller supplies the id column for an UPDATE).
     */
    fun recordAuctionUpsert(id: Int?, columns: Map<String, Any?>): Int {
        if (id == null) {
            val allocated = auctionIdAllocator()
            auctionUpserts.add(AuctionUpsert(id = null, allocatedId = allocated, columns = columns))
            return allocated
        }
        auctionUpserts.add(AuctionUpsert(id = id, allocatedId = null, columns = columns))
        return id
    }

    /** Record an `ng_auction_bid` INSERT (T0.7). INSERT-only — outbid rows are NEVER deleted/deduped. */
    fun recordAuctionBidInsert(columns: Map<String, Any?>) {
        auctionBidInserts.add(AuctionBidInsert(columns))
    }

    /** The recorded ng_auction UPSERTs (the T0.7 flush source), in emit order. */
    fun auctionUpserts(): List<AuctionUpsert> = auctionUpserts.toList()

    /** The recorded ng_auction_bid INSERTs (the T0.7 flush source), in emit order. */
    fun auctionBidInserts(): List<AuctionBidInsert> = auctionBidInserts.toList()

    /**
     * Tombstone a general (`General.php:515-600` kill: storeOldGeneral → DELETE
     * general/general_turn/rank_data). The recorder is the SOLE emitter:
     *  1. capture the pre-delete row (the `ng_old_generals` storeOldGeneral content),
     *  2. drop any pending UPDATE/rank patch (kill() clears updatedVar, `General.php:595` — no
     *     double-apply: the row leaves the update-set and lands ONLY as a delete),
     *  3. record the tombstone so a trailing [diffGeneral] for this id is a no-op, and
     *  4. drop it from the world (which feeds `DirtyState.deletedGenerals`).
     *
     * A general created *and* killed within the same tick fully cancels in [InMemoryTurnWorld.removeGeneral]
     * (no archive write for a row that never persisted) — so the snapshot is only kept when the world
     * actually held the row.
     */
    fun markGeneralDeleted(world: InMemoryTurnWorld, generalId: Int): Boolean {
        val existing = world.getGeneralById(generalId) ?: return false
        oldGeneralSnapshots.add(existing)
        generalPatches.remove(generalId)
        rankPatches.remove(generalId)
        deletedGeneralIds.add(generalId)
        return world.removeGeneral(generalId)
    }

    /**
     * Tombstone a nation (the nation cascade: DELETE diplomacy/nation_turn/nation + `ng_old_nations`
     * archive). The recorder is the SOLE emitter:
     *  1. capture the nation + its general ids (the `ng_old_nations` snapshot, `DatabaseHooks` step-2),
     *  2. revert every captured city to neutral as a city patch (nation=0, front_state=0, conflict
     *     removed — mirrors `FoundingCascade.neutralizeCity`),
     *  3. drop any pending nation UPDATE patch + record the tombstone (no double-apply), and
     *  4. drop the nation from the world (which feeds `DirtyState.deletedNations` and prunes diplomacy).
     */
    fun markNationDeleted(world: InMemoryTurnWorld, nationId: Int): Boolean {
        val nation = world.getNationById(nationId) ?: return false
        val ownedGeneralIds = world.listGenerals().filter { it.nationId == nationId }.map { it.id }
        nationSnapshots.add(DeletedNationSnapshot(nation, ownedGeneralIds, Instant.now()))

        // revert the nation's cities to neutral as recorded city patches (the cascade side effect).
        for (city in world.listCities().filter { it.nationId == nationId }) {
            val pre = opensamguk.engine.turn.PerTurnOverlay.toLogicCity(city)
            val nextMeta = LinkedHashMap(pre.meta)
            nextMeta.remove("conflict")
            // conflict now rides the dedicated city.conflict column (Task FU3) — reset it to '{}'
            // alongside the legacy meta-key cleanup so the neutralize is byte-faithful either way.
            val neutral = pre.copy(nationId = 0, frontState = 0, conflict = "{}", meta = nextMeta)
            diffCity(pre, neutral)
            // keep the world's read-state consistent (dirty-free: the city patch owns dirtiness).
            world.applyCityDirtyFree(city.copy(nationId = 0, frontState = 0, meta = nextMeta))
        }

        nationPatches.remove(nationId)
        deletedNationIds.add(nationId)
        return world.removeNation(nationId)
    }

    private fun diffCol(out: LinkedHashMap<String, Any?>, name: String, pre: Any?, post: Any?) {
        if (pre != post) out[name] = post
    }

    /**
     * Deep-diff `meta` at the key level. Returns ONLY the changed/added keys, walking the
     * post-state in its insertion order (so the patch — and the jsonb it flushes — preserves PHP
     * `Json::encode` key order). Removed keys are not expected in the P1 slice (the che resolver
     * only sets/bumps keys), so they are not modeled here.
     */
    private fun diffMeta(pre: Map<String, Any?>, post: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in post) {
            if (!pre.containsKey(k) || pre[k] != v) out[k] = v
        }
        return out
    }
}

/**
 * Default 1-based monotonic id source for the mailbox channel (T0.5). The daemon replaces it with a
 * DB-seeded allocator at rehydrate (max(message.id)+1) so the in-memory id matches the flushed SERIAL.
 */
private class AtomicCounter(start: Int = 1) {
    private var n = start - 1
    fun next(): Int = ++n
}
