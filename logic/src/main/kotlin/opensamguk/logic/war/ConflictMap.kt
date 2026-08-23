package opensamguk.logic.war

/**
 * A4 — port target = PHP `WarUnitCity.php:152-181` (addConflict), `process_war.php:521-530`
 * (getConquerNation = `array_key_first`, DeleteConflict = `unset` then no re-sort). Research Units 5/6,
 * consolidated OQ #4, decision #6.
 *
 * The city's `conflict` jsonb is an INSERTION-ordered `nation → weighted-dead` map (`LinkedHashMap`,
 * NEVER re-keyed by nation id). The siege winner = the first key after an arsort (stable DESC by value).
 *
 * ## Parity-critical details (PHP frozen historical baseline (ADR-LITE-042; not current product authority), NO RNG draws — pure arithmetic + a stable re-sort)
 *  - `dead = max(1, this.dead)` int floor (`:159`);
 *  - 선타(first-hit, `!$conflict`) / 막타(killing blow, `hp==0`) BONUS: `if(!$conflict || hp==0) dead *= 1.05`
 *    (`:161-163`) — a SINGLE ×1.05 even when BOTH guards hold (OR, never ×1.1025); stored as the raw
 *    IEEE-754 product, NO rounding;
 *  - empty map → `[nation => dead]`, `newConflict = false` (no 분쟁 log) (`:165-167`);
 *  - existing key → `+= dead; arsort`, `newConflict = false` (`:168-171`);
 *  - new nation on a NON-empty map → `= dead; arsort; newConflict = true` (triggers the 분쟁 log) (`:172-176`).
 *
 * ## arsort tie-break (decision #6, OQ #4)
 * PHP 8.0+ made every sort (incl. `arsort`) UNCONDITIONALLY stable (Stable sorting RFC; composer pins
 * php 8.3) at ANY map size — equal values keep their pre-sort (insertion) relative order. So this is a
 * `sortedByDescending` on a `LinkedHashMap` whose iterator is insertion-ordered → Kotlin's `sortedBy*` is
 * itself stable, giving DESC-by-value-with-insertion-order-on-ties for free. (Do NOT add a secondary id
 * comparator — that would DIVERGE from PHP on ties.)
 *
 * ## PHP int-vs-float fidelity (byte-equal JSON, gate (c))
 * `Json::encode` preserves PHP's int-vs-float distinction: a value that never touched ×1.05 nor a float
 * sum encodes as a bare int (`"40"`); a ×1.05 product or a float-tainted sum encodes as a float (`"42.0"`).
 * The plan mandates `Double` weights, so a parallel [isFloat] flag tracks PHP's runtime type per key to
 * reproduce the byte-exact encoding.
 */
class ConflictMap private constructor(
    private val weights: LinkedHashMap<Int, Double>,
    private val isFloat: LinkedHashMap<Int, Boolean>,
) {

    constructor() : this(LinkedHashMap(), LinkedHashMap())

    /**
     * PHP `WarUnitCity::addConflict` (`:152-181`). Returns `newConflict` — true ONLY when a NEW nation
     * joins a non-empty conflict (the 분쟁 global-log trigger). NO RNG.
     *
     * @param attackerNationId the opposing nation (`$oppose->getNationVar('nation')`).
     * @param dead the city's accumulated battle dead (`$this->dead`); floored to `max(1, dead)`.
     * @param cityHp the city's CURRENT HP (`$this->getHP()`); `== 0` ⇒ 막타 (killing blow) bonus.
     * @param isEmptyBefore whether the conflict map was empty before this call (`!$conflict`); ⇒ 선타 + empty branch.
     */
    fun addConflict(attackerNationId: Int, dead: Int, cityHp: Int, isEmptyBefore: Boolean): Boolean {
        // `$dead = max(1, $this->dead)` — int floor.
        var deadV: Double = maxOf(1, dead).toDouble()
        var deadIsFloat = false

        // 선타(!$conflict) / 막타(hp==0) bonus — a SINGLE ×1.05, makes the value a PHP float.
        if (isEmptyBefore || cityHp == 0) {
            deadV *= 1.05
            deadIsFloat = true
        }

        if (isEmptyBefore) {
            // empty branch: `$conflict = [$nationID => $dead]` — newConflict stays false, NO arsort.
            weights[attackerNationId] = deadV
            isFloat[attackerNationId] = deadIsFloat
            return false
        }

        if (weights.containsKey(attackerNationId)) {
            // existing key: `$conflict[$nationID] += $dead; arsort($conflict);`
            weights[attackerNationId] = weights.getValue(attackerNationId) + deadV
            // PHP int += float ⇒ float; int += int ⇒ int. Float taint is sticky.
            isFloat[attackerNationId] = (isFloat[attackerNationId] == true) || deadIsFloat
            arsort()
            return false
        }

        // new nation on a non-empty map: `$conflict[$nationID] = $dead; arsort($conflict);` → 분쟁 log.
        weights[attackerNationId] = deadV
        isFloat[attackerNationId] = deadIsFloat
        arsort()
        return true
    }

    /** PHP `getConquerNation` (`process_war.php:526-530`) = `array_key_first($conflict)` — null on empty. */
    fun getConquerNation(): Int? = weights.keys.firstOrNull()

    /**
     * PHP `DeleteConflict` per-city body (`process_war.php:518`) = `unset($conflict[$nation])` then re-encode
     * WITHOUT a re-sort — the surviving entries keep their current (already-sorted) relative order.
     */
    fun deleteConflict(nationId: Int) {
        weights.remove(nationId)
        isFloat.remove(nationId)
    }

    /** Test/consumer accessor — the weight stored for a nation, or null if absent. */
    fun weightOf(nationId: Int): Double? = weights[nationId]

    /** Test/consumer accessor — the current insertion/sort order of nation keys. */
    fun keysInOrder(): List<Int> = weights.keys.toList()

    /**
     * PHP `arsort($conflict)` — stable DESC by value, insertion order preserved on ties (decision #6,
     * PHP 8.0+ unconditional stability). Kotlin `sortedByDescending` is stable, so re-inserting in that
     * order into a fresh LinkedHashMap reproduces arsort exactly.
     */
    private fun arsort() {
        val sortedKeys = weights.entries.sortedByDescending { it.value }.map { it.key }
        val newWeights = LinkedHashMap<Int, Double>(weights.size)
        val newFloat = LinkedHashMap<Int, Boolean>(isFloat.size)
        for (k in sortedKeys) {
            newWeights[k] = weights.getValue(k)
            newFloat[k] = isFloat.getValue(k)
        }
        weights.clear(); weights.putAll(newWeights)
        isFloat.clear(); isFloat.putAll(newFloat)
    }

    /**
     * PHP `Json::encode($conflict)` byte-faithful — compact (no spaces), integer keys rendered as STRINGS
     * (PHP arrays with int keys ⇒ JSON object string keys), values as PHP-shortest int/float forms in the
     * stored insertion order. An empty map encodes to `"{}"` (matching the V1 `city.conflict` default).
     */
    fun encode(): String {
        if (weights.isEmpty()) return "{}"
        return weights.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":${formatValue(k, v)}"
        }
    }

    private fun formatValue(key: Int, v: Double): String =
        if (isFloat[key] == true) phpFloat(v) else v.toLong().toString()

    companion object {
        /**
         * PHP `Json::decode` of a `city.conflict` string into a [ConflictMap], preserving key order. A JSON
         * number with a decimal point / exponent is a PHP float (keeps [isFloat]); a bare integer is a PHP int.
         */
        fun decode(json: String): ConflictMap {
            val weights = LinkedHashMap<Int, Double>()
            val isFloat = LinkedHashMap<Int, Boolean>()
            val body = json.trim()
            if (body == "{}" || body.isEmpty()) return ConflictMap(weights, isFloat)
            val inner = body.removePrefix("{").removeSuffix("}")
            for (pair in splitTopLevel(inner)) {
                val colon = pair.indexOf(':')
                val rawKey = pair.substring(0, colon).trim().trim('"')
                val rawVal = pair.substring(colon + 1).trim()
                val key = rawKey.toInt()
                weights[key] = rawVal.toDouble()
                isFloat[key] = rawVal.contains('.') || rawVal.contains('e') || rawVal.contains('E')
            }
            return ConflictMap(weights, isFloat)
        }

        /** Split a flat object body on top-level commas (the conflict map has no nested structures). */
        private fun splitTopLevel(inner: String): List<String> =
            if (inner.isBlank()) emptyList() else inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * PHP `Json::encode` float rendering as the committed `conflict-01.json` golden proves it
         * (`WarUnitCity.php` conflict path, `Json::encode`): a float whose value is a WHOLE number is rendered
         * WITHOUT a fractional part — `3000*1.05 = 3150.0` encodes as `3150` (golden `{"1":3150}`), not
         * `3150.0`. (devsam's `Json::encode` follows PHP `json_encode` with the project's precision config,
         * which drops the trailing `.0` on integral floats.) A genuinely fractional float keeps its shortest
         * round-trip decimal. So: integral → bare integer string; fractional → `Double.toString` (shortest).
         */
        private fun phpFloat(v: Double): String =
            if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}
