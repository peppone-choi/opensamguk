package opensamguk.engine.flush

/**
 * A recorded flush operation. NO real SQL in P0-B — the recorder is the seam.
 * Design §0.1 #3 (the daemon write path never touches a JPA EntityManager) is
 * structurally trivial because the flush stub's only sink is a [FlushOpRecorder].
 * P1 swaps the recorder for a JDBC-batch executor, never JPA.
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
