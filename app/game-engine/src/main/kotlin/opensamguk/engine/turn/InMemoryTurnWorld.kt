package opensamguk.engine.turn

import java.time.Instant

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
)

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
class InMemoryTurnWorld(snapshot: WorldSnapshot) {
    private val generals = LinkedHashMap<Int, TurnGeneral>()
    private val cities = LinkedHashMap<Int, City>()
    private val nations = LinkedHashMap<Int, Nation>()
    private val troops = LinkedHashMap<Int, Troop>()
    private val diplomacy = LinkedHashMap<String, TurnDiplomacy>()

    private val dirtyGeneralIds = LinkedHashSet<Int>()
    private val dirtyCityIds = LinkedHashSet<Int>()
    private val dirtyNationIds = LinkedHashSet<Int>()
    private val dirtyTroopIds = LinkedHashSet<Int>()
    private val dirtyDiplomacyKeys = LinkedHashSet<String>()

    private val createdGeneralIds = LinkedHashSet<Int>()
    private val createdNationIds = LinkedHashSet<Int>()
    private val createdTroopIds = LinkedHashSet<Int>()
    private val createdDiplomacyKeys = LinkedHashSet<String>()

    private val deletedTroopIds = LinkedHashSet<Int>()
    private val deletedGeneralIds = LinkedHashSet<Int>()
    private val deletedNationIds = LinkedHashSet<Int>()
    private val deletedNationSnapshots = mutableListOf<DeletedNationSnapshot>()
    private val logs = mutableListOf<LogEntryDraft>()

    private var state: TurnWorldState

    init {
        state = snapshot.state
        for (general in snapshot.generals) generals[general.id] = general
        for (city in snapshot.cities) cities[city.id] = city
        for (nation in snapshot.nations) nations[nation.id] = nation
        for (troop in snapshot.troops) troops[troop.id] = troop
        for (entry in snapshot.diplomacy) {
            diplomacy[buildDiplomacyKey(entry.fromNationId, entry.toNationId)] = entry
        }
    }

    fun getState(): TurnWorldState = state

    // Getters return defensive copies. Data classes are immutable so the value itself is
    // safe; returning fresh lists prevents the caller from mutating the internal collections.
    fun listGenerals(): List<TurnGeneral> = generals.values.toList()
    fun listCities(): List<City> = cities.values.toList()
    fun listNations(): List<Nation> = nations.values.toList()
    fun listTroops(): List<Troop> = troops.values.toList()
    fun listDiplomacy(): List<TurnDiplomacy> = diplomacy.values.toList()

    fun getGeneralById(id: Int): TurnGeneral? = generals[id]
    fun getCityById(id: Int): City? = cities[id]
    fun getNationById(id: Int): Nation? = nations[id]
    fun getTroopById(id: Int): Troop? = troops[id]

    fun pushLog(entry: LogEntryDraft) {
        logs.add(entry)
    }

    fun updateGeneral(next: TurnGeneral): TurnGeneral? {
        if (!generals.containsKey(next.id)) return null
        generals[next.id] = next
        dirtyGeneralIds.add(next.id)
        return next
    }

    fun createGeneral(general: TurnGeneral): TurnGeneral {
        generals[general.id] = general
        dirtyGeneralIds.add(general.id)
        createdGeneralIds.add(general.id)
        return general
    }

    fun removeGeneral(id: Int): Boolean {
        if (!generals.containsKey(id)) return false
        generals.remove(id)
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
            diplomacy.remove(key)
            dirtyDiplomacyKeys.remove(key)
            createdDiplomacyKeys.remove(key)
        }
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

    fun setCurrentDate(year: Int, month: Int) {
        state = state.copy(
            currentYear = year,
            currentMonth = month,
            meta = state.meta + mapOf("currentYear" to year, "currentMonth" to month),
        )
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
        )
    }
}
