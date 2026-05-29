package opensamguk.engine.flush

/**
 * A recorded flush op-tag — the **test seam** for the write ORDER, NOT the real SQL sink.
 *
 * In P1 the real daemon write path is [DatabaseHooks.flushChanges] (the
 * [opensamguk.infra.persistence.JdbcFlushExecutor] overload): it maps the world's [DirtyState]
 * → an [opensamguk.infra.persistence.FlushPayload] ([opensamguk.engine.turn.DirtyState]) and runs the EXACT ordered contract as plain
 * JDBC inside ONE transaction — never a JPA `EntityManager` (design §0.1 #3, enforced by
 * [DaemonNoEntityManagerTest] reading [DaemonWriteGuard]). The [FlushOpRecorder] is kept as the
 * pure, DB-free oracle the order tests ([DatabaseHooksOrderTest]) assert against.
 */
data class FlushOp(val table: String, val verb: Verb, val count: Int) {
    enum class Verb { UPDATE, UPSERT, CREATE_MANY, DELETE_MANY }
}

class FlushOpRecorder {
    private val ops = mutableListOf<FlushOp>()

    /**
     * Records an op, skipping a `count <= 0` no-op. This mirrors the `if (xs.length > 0)`
     * createMany/deleteMany guards AND the empty-`.map()` semantics of the `Promise.all`
     * update batch in `databaseHooks.ts`: an UPDATE/UPSERT over zero filtered rows issues no
     * DB call. The two always-on ops (worldState.update, reservedTurns.flush) use [recordAlways].
     */
    fun record(table: String, verb: FlushOp.Verb, count: Int) {
        if (count <= 0) return
        ops.add(FlushOp(table, verb, count))
    }

    /** Records unconditionally (e.g. the single worldState.update that always fires). */
    fun recordAlways(table: String, verb: FlushOp.Verb, count: Int) {
        ops.add(FlushOp(table, verb, count))
    }

    fun ops(): List<FlushOp> = ops.toList()
}
