package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.world.WaterControlSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val phpStartTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * Initial snapshot used to seed [InMemoryTurnWorld].
 */
data class WorldSnapshot(
    val state: TurnWorldState,
    val generals: List<TurnGeneral> = emptyList(),
    val cities: List<City> = emptyList(),
    val nations: List<Nation> = emptyList(),
    val troops: List<Troop> = emptyList(),
    val diplomacy: List<TurnDiplomacy> = emptyList(),
    val accessLogs: List<GeneralAccessLog> = emptyList(),
    val archivedNationIds: List<Int> = emptyList(),
    val serverId: String? = state.serverId,
    val worldId: WorldId,
    val waterControlSnapshot: WaterControlSnapshot? = null,
) {
    init {
        require(state.id == worldId.value) {
            "WorldSnapshot state.id=${state.id} must equal worldId=${worldId.value}"
        }
        if (waterControlSnapshot != null) {
            require(ActiveWorldMap.requireName(state.config, state.meta) == "han-world-v3") {
                "Water control is only supported by the explicit Han V3 map"
            }
        }
    }
}

/**
 * Faithful transcription of the load-bearing dirty/created/deleted machinery in
 * `app/game-engine/src/turn/inMemoryWorld.ts` (the turn-orchestration extension points
 * — handlers, calendar, diplomacy-matrix auto-fill — are out of scope for P0-B).
 *
 * Load-bearing invariants reproduced verbatim:
 *  - [consumeDirtyState] is single-shot: it drains the dirty/created/deleted sets and then
 *    clears them, so a second call returns empty collections.
 *  - getters return defensive copies (mutating the returned object never mutates internal state).
 *  - create-then-delete within the same tick cancels: `remove*` prunes the matching `created*Ids`,
 *    so neither a created row nor a deleted row is emitted.
 *  - [removeNation] also prunes that nation's diplomacy entries from all dirty/created sets.
 */
interface LegacyDiplomacyIdentityOracle {
    fun assignCreatedNo(entry: TurnDiplomacy, proposedNo: Int?): Int?

    fun observeDeleted(entry: TurnDiplomacy, nationId: Int)
}

class InMemoryTurnWorld(
    snapshot: WorldSnapshot,
    private val legacyDiplomacyIdentityOracle: LegacyDiplomacyIdentityOracle? = null,
) {
    val worldId: WorldId = snapshot.worldId
    private val generals = LinkedHashMap<Int, TurnGeneral>()
    private val generalIdentityTokens = LinkedHashMap<Int, Long>()
    private var nextGeneralIdentityToken = 0L
    private val cities = LinkedHashMap<Int, City>()
    private val nations = LinkedHashMap<Int, Nation>()
    private val troops = LinkedHashMap<Int, Troop>()
    private val diplomacy = LinkedHashMap<String, TurnDiplomacy>()
    private val accessLogs = LinkedHashMap<Int, GeneralAccessLog>()

    private val dirtyGeneralIds = LinkedHashSet<Int>()
    private val dirtyCityIds = LinkedHashSet<Int>()
    private val dirtyNationIds = LinkedHashSet<Int>()
    private val dirtyTroopIds = LinkedHashSet<Int>()
    private val dirtyDiplomacyKeys = LinkedHashSet<String>()

    private val createdGeneralIds = LinkedHashSet<Int>()
    private val createdNationIds = LinkedHashSet<Int>()
    private val createdTroopIds = LinkedHashSet<Int>()
    private val createdDiplomacyKeys = LinkedHashSet<String>()

    // nation_turn has NO in-memory map — it is an INSERT-only ledger (like the rank/kv channels), not a
    // queryable world entity in the slice. The founding created-set (거병 INSERTs 24 rows) appends here;
    // [consumeDirtyState] drains it into [DirtyState.nationTurnDirty].
    private val createdNationTurns = mutableListOf<NationTurn>()

    private val deletedTroopIds = LinkedHashSet<Int>()
    private val deletedGeneralIds = LinkedHashSet<Int>()
    private val deletedNationIds = LinkedHashSet<Int>()
    private val deletedNationSnapshots = mutableListOf<DeletedNationSnapshot>()
    private val logs = mutableListOf<LogEntryDraft>()

    // 액추에이터/어드민 HTTP 스레드가 데몬 스레드의 `state = state.copy(...)`와 동시에 읽는다.
    // [TurnWorldState]는 불변 data class라 torn object는 없지만, @Volatile 없이는 가시성 보장이 없다.
    @Volatile private var state: TurnWorldState
    @Volatile private var waterControl: WaterControlSnapshot? = snapshot.waterControlSnapshot
    private val serverId: String?

    /**
     * Persistent monotonic high-water marks for engine-assigned ids. Unlike MySQL `AUTO_INCREMENT`,
     * opensamguk assigns `nation.id` and `general.id` engine-side, so the next free id must survive
     * restarts. These are seeded from `world_state.meta` (if previously flushed) and from the live
     * snapshot, then bumped by [allocateNationId]/[allocateGeneralId] and persisted each tick.
     */
    private var maxNationId: Int
    private var maxGeneralId: Int
    private var maxLegacyDiplomacyNo: Int?

    init {
        serverId = snapshot.serverId ?: snapshot.state.serverId
        state = snapshot.state.copy(serverId = serverId)
        for (general in snapshot.generals) {
            generals[general.id] = general
            generalIdentityTokens[general.id] = ++nextGeneralIdentityToken
        }
        for (city in snapshot.cities) cities[city.id] = city
        for (nation in snapshot.nations) nations[nation.id] = nation
        for (troop in snapshot.troops) troops[troop.id] = troop
        for (entry in snapshot.diplomacy) {
            diplomacy[buildDiplomacyKey(entry.fromNationId, entry.toNationId)] = entry
        }
        for (entry in snapshot.accessLogs) accessLogs[entry.generalId] = entry
        maxNationId = maxOf(
            snapshot.nations.maxOfOrNull { it.id } ?: 0,
            snapshot.archivedNationIds.maxOrNull() ?: 0,
            (snapshot.state.meta["maxNationId"] as? Number)?.toInt() ?: 0,
        )
        maxGeneralId = maxOf(
            snapshot.generals.maxOfOrNull { it.id } ?: 0,
            (snapshot.state.meta["maxGeneralId"] as? Number)?.toInt() ?: 0,
        )
        maxLegacyDiplomacyNo = snapshot.diplomacy
            .mapNotNull { (it.meta["no"] as? Number)?.toInt() }
            .maxOrNull()
        // Persist the seeded high-water marks immediately so a world that never allocates a new id
        // still flushes the previous maximum back into world_state.meta (restart parity).
        recordMaxNationId()
        recordMaxGeneralId()
    }

    fun getState(): TurnWorldState = state

    /** Immutable read projection; legacy worlds have no water state, absent V3 rows stay unknown. */
    fun waterControlSnapshot(): WaterControlSnapshot? = waterControl

    /** Only ChangeRecorder calls this; it remains the sole dirty source. */
    internal fun applyWaterControlDirtyFree(snapshot: WaterControlSnapshot) {
        val current = requireNotNull(waterControl) { "World has no water topology" }
        require(snapshot.topologyRevision == current.topologyRevision && snapshot.topologyHash == current.topologyHash &&
            snapshot.knownWaterZoneIds == current.knownWaterZoneIds) { "Cannot replace a world's immutable water topology" }
        waterControl = snapshot
    }
    fun archiveServerId(): String? = serverId

    /**
     * OPENSAM-131: after a successful fenced flush, advance the in-memory expected
     * [TurnWorldState.worldVersion] to match the committed row (version + 1).
     */
    fun advanceWorldVersionAfterCommit() {
        state = state.copy(worldVersion = state.worldVersion + 1)
    }


    // Getters return defensive copies. Data classes are immutable so the value itself is
    // safe; returning fresh lists prevents the caller from mutating the internal collections.
    fun listGenerals(): List<TurnGeneral> = generals.values.toList()
    fun listCities(): List<City> = cities.values.toList()
    fun listNations(): List<Nation> = nations.values.toList()
    fun listTroops(): List<Troop> = troops.values.toList()
    fun listDiplomacy(): List<TurnDiplomacy> = diplomacy.values.toList()
    fun listAccessLogs(): List<GeneralAccessLog> = accessLogs.values.toList()
    fun peekLogs(): List<LogEntryDraft> = logs.toList()

    fun getGeneralById(id: Int): TurnGeneral? = generals[id]
    fun getGeneralIdentityToken(id: Int): Long? = generalIdentityTokens[id]
    fun getCityById(id: Int): City? = cities[id]
    fun getNationById(id: Int): Nation? = nations[id]
    fun getTroopById(id: Int): Troop? = troops[id]
    fun getAccessLog(generalId: Int): GeneralAccessLog? = accessLogs[generalId]

    fun applyAccessLogDirtyFree(next: GeneralAccessLog) {
        accessLogs[next.generalId] = next
    }

    fun removeAccessLogDirtyFree(generalId: Int): Boolean = accessLogs.remove(generalId) != null

    fun pushLog(entry: LogEntryDraft) {
        logs.add(
            entry.copy(
                year = entry.year ?: state.currentYear,
                month = entry.month ?: state.currentMonth,
                phase = entry.phase ?: state.currentPhase,
            ),
        )
    }

    fun updateGeneral(next: TurnGeneral): TurnGeneral? {
        if (!generals.containsKey(next.id)) return null
        generals[next.id] = next
        dirtyGeneralIds.add(next.id)
        return next
    }

    fun createGeneral(general: TurnGeneral): TurnGeneral {
        generals[general.id] = general
        generalIdentityTokens[general.id] = ++nextGeneralIdentityToken
        dirtyGeneralIds.add(general.id)
        createdGeneralIds.add(general.id)
        if (general.id > maxGeneralId) {
            maxGeneralId = general.id
            recordMaxGeneralId()
        }
        return general
    }

    fun removeGeneral(id: Int): Boolean {
        if (!generals.containsKey(id)) return false
        generals.remove(id)
        generalIdentityTokens.remove(id)
        dirtyGeneralIds.remove(id)
        // create-then-delete in the same tick fully cancels: drop the pending create and
        // do NOT emit a delete for a row that was never persisted.
        val wasCreatedThisTick = createdGeneralIds.remove(id)
        if (!wasCreatedThisTick) {
            deletedGeneralIds.add(id)
        }
        return true
    }

    fun updateCity(next: City): City? {
        if (!cities.containsKey(next.id)) return null
        cities[next.id] = next
        dirtyCityIds.add(next.id)
        return next
    }

    /**
     * Replace a general's row WITHOUT marking it dirty (the **dirty-free apply path**, P1 Task F3).
     *
     * The reserved-turn handler applies the resolver's post-state through here so the world reads
     * reflect the mutation while [ChangeRecorder] stays the SINGLE dirty source (design Risk #4: two
     * dirty sources = silent flush divergence). The flush (F4) maps the recorder's patches → SQL; the
     * world's own dirty set is never the daemon write path. Returns null if the row is absent.
     */
    fun applyGeneralDirtyFree(next: TurnGeneral): TurnGeneral? {
        if (!generals.containsKey(next.id)) return null
        generals[next.id] = next
        return next
    }

    /** Replace a city's row WITHOUT marking it dirty — see [applyGeneralDirtyFree] (P1 Task F3). */
    fun applyCityDirtyFree(next: City): City? {
        if (!cities.containsKey(next.id)) return null
        cities[next.id] = next
        return next
    }

    /**
     * Replace a nation's row WITHOUT marking it dirty (T0.3 — the nation-command resolve path applies
     * the resolver's post-state through here so the world reads reflect it while [ChangeRecorder]
     * stays the SINGLE dirty source). Mirrors [applyGeneralDirtyFree]/[applyCityDirtyFree].
     */
    fun applyNationDirtyFree(next: Nation): Nation? {
        if (!nations.containsKey(next.id)) return null
        nations[next.id] = next
        return next
    }

    fun applyKvDirtyFree(key: KvKey, value: Any?) {
        when (key.table) {
            "game_env" -> {
                val nextMeta = LinkedHashMap(state.meta)
                if (value == null) nextMeta.remove(key.key) else nextMeta[key.key] = value
                state = state.copy(meta = nextMeta)
            }
            "nation_env" -> {
                val nationId = key.namespace.toIntOrNull() ?: return
                val nation = nations[nationId] ?: return
                val currentEnv = nation.meta["nation_env"] as? Map<*, *>
                val nextEnv = LinkedHashMap<String, Any?>()
                currentEnv?.forEach { (envKey, envValue) -> nextEnv[envKey.toString()] = envValue }
                if (value == null) nextEnv.remove(key.key) else nextEnv[key.key] = value
                val nextMeta = LinkedHashMap(nation.meta)
                nextMeta["nation_env"] = nextEnv
                nations[nationId] = nation.copy(meta = nextMeta)
            }
        }
    }

    /** Read a single diplomacy row by `(from, to)` (null if absent). */
    fun getDiplomacy(fromNationId: Int, toNationId: Int): TurnDiplomacy? =
        diplomacy[buildDiplomacyKey(fromNationId, toNationId)]

    /**
     * Apply a diplomacy `(from, to)` transition to the world's read-state (T0.4) WITHOUT marking the
     * world dirty — the [ChangeRecorder.diffDiplomacy] owns dirtiness (the SINGLE dirty source). A
     * bidirectional transition calls this TWICE (once per direction). `dead` defaults to the existing
     * value when not supplied. Returns the new row, or null if the `(from, to)` row is absent.
     */
    fun updateDiplomacy(fromNationId: Int, toNationId: Int, state: Int, term: Int, dead: Int? = null): TurnDiplomacy? {
        val key = buildDiplomacyKey(fromNationId, toNationId)
        val current = diplomacy[key] ?: return null
        val next = current.copy(state = state, term = term, dead = dead ?: current.dead)
        diplomacy[key] = next
        return next
    }

    fun updateNation(next: Nation): Nation? {
        if (!nations.containsKey(next.id)) return null
        nations[next.id] = next
        dirtyNationIds.add(next.id)
        return next
    }

    fun createTroop(troop: Troop): Troop {
        troops[troop.id] = troop
        dirtyTroopIds.add(troop.id)
        createdTroopIds.add(troop.id)
        return troop
    }

    /**
     * INSERT a brand-new nation (founding 거병). Mirrors [createTroop]: stages the row under its id, marks
     * it dirty AND created — [consumeDirtyState] returns it in `createdNations`, and [DatabaseHooks] excludes
     * it from the step-7 UPDATE batch (the `createdNationIds` filter) so it INSERTs rather than UPSERTs.
     * The id is the placeholder from [allocateNationId] — `nation.id` is an `integer PRIMARY KEY` (NOT
     * serial), so the placeholder id IS the authoritative id (no flush-time reconciliation).
     */
    fun createNation(nation: Nation): Nation {
        nations[nation.id] = nation
        dirtyNationIds.add(nation.id)
        createdNationIds.add(nation.id)
        if (nation.id > maxNationId) {
            maxNationId = nation.id
            recordMaxNationId()
        }
        return nation
    }

    /**
     * INSERT a brand-new diplomacy row (founding 거병 — one pair per existing nation). Mirrors
     * [createGeneral]: stages the row under its `(from, to)` key, marks it dirty AND created. The key
     * helper is [buildDiplomacyKey] (same as the snapshot init / [updateDiplomacy] path).
     */
    fun createDiplomacy(entry: TurnDiplomacy): TurnDiplomacy {
        var materialized = if (entry.meta["no"] == null && maxLegacyDiplomacyNo != null) {
            entry.copy(meta = entry.meta + ("no" to maxLegacyDiplomacyNo!!.plus(1).also { maxLegacyDiplomacyNo = it }))
        } else {
            (entry.meta["no"] as? Number)?.toInt()?.let { no ->
                maxLegacyDiplomacyNo = maxOf(maxLegacyDiplomacyNo ?: no, no)
            }
            entry
        }
        legacyDiplomacyIdentityOracle?.assignCreatedNo(
            materialized,
            (materialized.meta["no"] as? Number)?.toInt(),
        )?.let { assignedNo ->
            materialized = materialized.copy(meta = materialized.meta + ("no" to assignedNo))
            maxLegacyDiplomacyNo = maxOf(maxLegacyDiplomacyNo ?: assignedNo, assignedNo)
        }
        val key = buildDiplomacyKey(materialized.fromNationId, materialized.toNationId)
        diplomacy[key] = materialized
        dirtyDiplomacyKeys.add(key)
        createdDiplomacyKeys.add(key)
        return materialized
    }

    /**
     * Append a reserved nation-command row to the INSERT-only ledger (founding 거병 writes 24). There is no
     * in-memory nation_turn map to query in the slice; [consumeDirtyState] drains the appended rows into
     * [DirtyState.nationTurnDirty], which [DatabaseHooks] carries to the step-3 `nationTurnCreateMany`.
     */
    fun createNationTurn(turn: NationTurn) {
        createdNationTurns.add(turn)
    }

    /**
     * The next free nation id (`maxNationId + 1`). `nation.id` is `integer PRIMARY KEY` (NOT serial), so
     * opensamguk assigns the id engine-side — the placeholder IS the final id (no flush-time reconciliation).
     *
     * The high-water mark is seeded from `world_state.meta['maxNationId']` on boot and bumped here, so ids
     * deleted in earlier ticks are NOT reused after a restart (MySQL `AUTO_INCREMENT` parity). Same-tick
     * deletions are still handled: the live/deleted key max feeds the bump, so a row deleted this tick but
     * not yet flushed cannot collide with a new founding in the same tick.
     */
    fun allocateNationId(): Int {
        maxNationId = maxOf(
            maxNationId,
            nations.keys.maxOrNull() ?: 0,
            deletedNationIds.maxOrNull() ?: 0,
        ) + 1
        recordMaxNationId()
        return maxNationId
    }

    /**
     * Next free general id (`maxGeneralId + 1`). Mirrors [allocateNationId]: `general.id` is `serial PRIMARY KEY`
     * in the schema, but opensamguk assigns the id engine-side via explicit INSERT. The persistent high-water
     * mark prevents cross-tick reuse after restart.
     */
    fun allocateGeneralId(): Int {
        maxGeneralId = maxOf(
            maxGeneralId,
            generals.keys.maxOrNull() ?: 0,
            deletedGeneralIds.maxOrNull() ?: 0,
        ) + 1
        recordMaxGeneralId()
        return maxGeneralId
    }

    private fun recordMaxNationId() {
        state = state.copy(meta = state.meta + mapOf("maxNationId" to maxNationId))
    }

    private fun recordMaxGeneralId() {
        state = state.copy(meta = state.meta + mapOf("maxGeneralId" to maxGeneralId))
    }

    /**
     * Replace a troop's row (SetTroopName rename) — marks it dirty ONLY (not created), so the flush
     * routes it through the troop UPDATE batch. Mirrors [updateNation]. Returns null if absent.
     */
    fun updateTroop(next: Troop): Troop? {
        if (!troops.containsKey(next.id)) return null
        troops[next.id] = next
        dirtyTroopIds.add(next.id)
        return next
    }

    fun removeTroop(id: Int): Boolean {
        if (!troops.containsKey(id)) return false
        troops.remove(id)
        dirtyTroopIds.remove(id)
        val wasCreatedThisTick = createdTroopIds.remove(id)
        if (!wasCreatedThisTick) {
            deletedTroopIds.add(id)
        }
        return true
    }

    fun removeNation(id: Int): Boolean {
        if (!nations.containsKey(id)) return false
        nations.remove(id)
        dirtyNationIds.remove(id)
        val wasCreatedThisTick = createdNationIds.remove(id)
        if (!wasCreatedThisTick) {
            deletedNationIds.add(id)
        }
        val keysToRemove = diplomacy.entries
            .filter { (_, entry) -> entry.fromNationId == id || entry.toNationId == id }
            .map { it.key }
        for (key in keysToRemove) {
            diplomacy[key]?.let { legacyDiplomacyIdentityOracle?.observeDeleted(it, id) }
            diplomacy.remove(key)
            dirtyDiplomacyKeys.remove(key)
            createdDiplomacyKeys.remove(key)
        }
        // create-then-delete in the same tick cancels for the nation_turn ledger too (matches the
        // diplomacy prune above): drop the pending INSERTs so a never-persisted nation leaves no rows.
        createdNationTurns.removeAll { it.nationId == id }
        return true
    }

    fun recordDeletedNationSnapshot(snapshot: DeletedNationSnapshot) {
        deletedNationSnapshots.add(snapshot)
    }

    fun setLastTurnTime(turnTime: Instant) {
        state = state.copy(
            lastTurnTime = turnTime,
            meta = state.meta + mapOf("lastTurnTime" to turnTime.toString()),
        )
    }

    fun setCurrentDate(year: Int, month: Int, phase: Int = 1) {
        state = state.copy(
            currentYear = year,
            currentMonth = month,
            currentPhase = phase.coerceIn(1, 3),
            meta = state.meta + mapOf("currentYear" to year, "currentMonth" to month, "currentPhase" to phase.coerceIn(1, 3)),
        )
    }

    fun applyAdminWorldSettings(
        status: String?,
        configPatch: Map<String, Any?>,
        tickSeconds: Int?,
    ) {
        val nextConfig = LinkedHashMap(state.config)
        nextConfig.putAll(configPatch)
        val nextMeta = LinkedHashMap(state.meta)
        for ((key, value) in configPatch) nextMeta[key] = value
        canonicalStartTime(configPatch["starttime"] ?: configPatch["startTime"])?.let { startTime ->
            nextConfig["startTime"] = startTime
            nextMeta["startTime"] = startTime
        }
        state = state.copy(
            status = status ?: state.status,
            config = nextConfig,
            tickSeconds = tickSeconds ?: state.tickSeconds,
            meta = nextMeta,
        )
    }

    fun setGameEnvValue(key: String, value: Any?) {
        state = state.copy(meta = state.meta + mapOf(key to value))
    }

    private fun canonicalStartTime(value: Any?): String? {
        val raw = value?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val instant = runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw, phpStartTimeFormat).atZone(seoulZone).toInstant() }.getOrNull()
        return instant?.toString()
    }

    /**
     * `$gameStor->isunited = value` (Q14 checkEmperior / InvaderEnding). meta 에 in-memory 반영하고,
     * flush payload가 world_state.isunited 컬럼에 저장한다. boot-load는 전용 컬럼을 meta로 복원하므로
     * [getState] 를 읽는 MonthlyPostUpdateHook·TurnDaemonLifecycle과 재기동 스냅샷이 같은 값을 본다.
     */
    fun setIsunited(value: Int) {
        state = state.copy(meta = state.meta + mapOf("isunited" to value))
    }

    /**
     * `$gameStor->refreshLimit = $gameStor->refreshLimit * factor` (InvaderEnding.php:65). meta 에만
     * in-memory 반영한다. isunited 영속화와 달리 game_env `refreshLimit` KV flush/load는 별도 갭이다
     * (LEDGER 백로그: game_env KV write seam 부재). 부재 시 0 기준(평상시 refreshLimit
     * 가 boot 에서 안 실리면 곱셈 결과 0 — PHP 도 NULL*100=0 동치). [getState] meta["refreshLimit"] 즉시 반영.
     */
    fun multiplyRefreshLimit(factor: Int) {
        val cur = (state.meta["refreshLimit"] as? Number)?.toInt() ?: 0
        state = state.copy(meta = state.meta + mapOf("refreshLimit" to cur * factor))
    }

    /**
     * Single-shot drain: collects the dirty/created/deleted sets into a [DirtyState] and then
     * clears every source set so the next call returns empty collections.
     */
    fun consumeDirtyState(): DirtyState {
        val generalsOut = dirtyGeneralIds.mapNotNull { generals[it] }
        val createdGenerals = createdGeneralIds.mapNotNull { generals[it] }
        val createdNations = createdNationIds.mapNotNull { nations[it] }
        val citiesOut = dirtyCityIds.mapNotNull { cities[it] }
        val nationsOut = dirtyNationIds.mapNotNull { nations[it] }
        val troopsOut = dirtyTroopIds.mapNotNull { troops[it] }
        val diplomacyOut = dirtyDiplomacyKeys.mapNotNull { diplomacy[it] }
        val createdTroops = createdTroopIds.mapNotNull { troops[it] }
        val createdDiplomacy = createdDiplomacyKeys.mapNotNull { diplomacy[it] }
        val createdNationTurnsOut = createdNationTurns.toList()
        val deletedTroops = deletedTroopIds.toList()
        val deletedGenerals = deletedGeneralIds.toList()
        val deletedNations = deletedNationIds.toList()
        val deletedSnapshots = deletedNationSnapshots.toList()
        val logsOut = logs.toList()

        dirtyGeneralIds.clear()
        dirtyCityIds.clear()
        dirtyNationIds.clear()
        dirtyTroopIds.clear()
        dirtyDiplomacyKeys.clear()
        createdGeneralIds.clear()
        createdNationIds.clear()
        createdTroopIds.clear()
        createdDiplomacyKeys.clear()
        createdNationTurns.clear()
        deletedTroopIds.clear()
        deletedGeneralIds.clear()
        deletedNationIds.clear()
        deletedNationSnapshots.clear()
        logs.clear()

        return DirtyState(
            generals = generalsOut,
            cities = citiesOut,
            nations = nationsOut,
            troops = troopsOut,
            deletedTroops = deletedTroops,
            deletedGenerals = deletedGenerals,
            deletedNations = deletedNations,
            deletedNationSnapshots = deletedSnapshots,
            diplomacy = diplomacyOut,
            logs = logsOut,
            createdGenerals = createdGenerals,
            createdNations = createdNations,
            createdTroops = createdTroops,
            createdDiplomacy = createdDiplomacy,
            nationTurnDirty = createdNationTurnsOut,
        )
    }
}
