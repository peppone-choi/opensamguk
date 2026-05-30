package opensamguk.logic.war

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port target = PHP `WarUnitCity.php:152-181` (addConflict), `process_war.php:521-530`
 * (getConquerNation = array_key_first / DeleteConflict = unset, no re-sort). Research Units 5/6,
 * consolidated OQ #4, decision #6.
 *
 * NO RNG draws — pure arithmetic + an arsort-equivalent stable-DESC re-sort. The headline parity risk
 * is the arsort tie-break: PHP 8.0+ sort is UNCONDITIONALLY stable (Stable sorting RFC), so equal values
 * keep their pre-sort relative (insertion) order at any map size. This test PINS that determinism
 * directly (the G1 2-3-nation siege golden pins the byte-equal JSON on top).
 */
class ConflictMapTest {

    // --- addConflict: dead floor + ×1.05 선타/막타 + empty/accumulate/new-nation branches --------------

    @Test
    fun `dead is floored to max 1`() {
        // Empty-map branch IS the 선타 (first-hit) site: PHP `if(!$conflict || hp==0) dead *= 1.05` — an
        // empty conflict (`!$conflict`) ALWAYS fires the bonus. dead=0 → max(1,0)=1 → ×1.05 = 1.05.
        val m = ConflictMap()
        val newConflict = m.addConflict(attackerNationId = 7, dead = 0, cityHp = 50, isEmptyBefore = true)
        assertFalse(newConflict)
        assertEquals(1.05, m.weightOf(7))
    }

    @Test
    fun `선타 bonus applies single ×1_05 on empty-history first hit when not via isEmptyBefore`() {
        // PHP: `if(!$conflict || hp==0) dead *= 1.05`. The 막타 (hp==0) branch fires even on an empty map.
        val m = ConflictMap()
        val newConflict = m.addConflict(attackerNationId = 1, dead = 100, cityHp = 0, isEmptyBefore = true)
        assertFalse(newConflict) // empty branch → newConflict false
        // max(1,100)=100, hp==0 → ×1.05 = 105.0 (single multiply, NOT 1.1025)
        assertEquals(105.0, m.weightOf(1))
    }

    @Test
    fun `선타 and 막타 both true apply only a SINGLE ×1_05 not squared`() {
        // isEmptyBefore=true AND cityHp==0 → the single OR-guarded multiply (NOT ×1.1025).
        val m = ConflictMap()
        m.addConflict(attackerNationId = 2, dead = 200, cityHp = 0, isEmptyBefore = true)
        assertEquals(210.0, m.weightOf(2)) // 200 × 1.05, applied ONCE
    }

    @Test
    fun `no bonus when not first hit and hp positive`() {
        // Pre-existing conflict for a DIFFERENT nation, hp>0 → no ×1.05 on this nation's first contribution.
        val m = ConflictMap()
        m.addConflict(attackerNationId = 9, dead = 10, cityHp = 30, isEmptyBefore = true) // seeds the map
        val newConflict = m.addConflict(attackerNationId = 5, dead = 40, cityHp = 30, isEmptyBefore = false)
        assertTrue(newConflict) // new nation on a non-empty map → 분쟁 log
        assertEquals(40.0, m.weightOf(5)) // raw, no ×1.05
    }

    @Test
    fun `accumulate adds to existing nation key`() {
        val m = ConflictMap()
        m.addConflict(attackerNationId = 3, dead = 10, cityHp = 30, isEmptyBefore = true) // 선타: 10×1.05 = 10.5
        // Same nation hits again: key exists branch → += dead (no ×1.05 since not first/막타).
        val newConflict = m.addConflict(attackerNationId = 3, dead = 25, cityHp = 30, isEmptyBefore = false)
        assertFalse(newConflict) // existing key → NOT a new conflict
        assertEquals(35.5, m.weightOf(3)) // 10.5 + 25
    }

    @Test
    fun `막타 bonus accumulates onto an existing key`() {
        val m = ConflictMap()
        m.addConflict(attackerNationId = 4, dead = 10, cityHp = 30, isEmptyBefore = true) // 선타: 10×1.05 = 10.5
        // Same nation, but this is the killing blow (hp==0) → ×1.05 on this contribution.
        m.addConflict(attackerNationId = 4, dead = 100, cityHp = 0, isEmptyBefore = false)
        assertEquals(115.5, m.weightOf(4)) // 10.5 + 100×1.05
    }

    // --- newConflict asymmetry (the 분쟁 log trigger) ---------------------------------------------------

    @Test
    fun `newConflict is false on empty-map branch and on existing-key branch, true only on new nation`() {
        val m = ConflictMap()
        assertFalse(m.addConflict(1, 10, 30, isEmptyBefore = true))   // empty branch
        assertFalse(m.addConflict(1, 10, 30, isEmptyBefore = false))  // existing key
        assertTrue(m.addConflict(2, 10, 30, isEmptyBefore = false))   // new nation, non-empty
    }

    // --- arsort: stable DESC by value, insertion order preserved on ties (decision #6) -----------------

    @Test
    fun `arsort orders DESC by value with insertion order on ties for any map size`() {
        val m = ConflictMap()
        // Insert in an order that forces a re-sort and a tie. Values chosen so two nations tie at 50.0.
        m.addConflict(10, 30, 99, isEmptyBefore = true)   // 10 -> 30.0
        m.addConflict(20, 50, 99, isEmptyBefore = false)  // 20 -> 50.0 (new nation, arsort)
        m.addConflict(30, 50, 99, isEmptyBefore = false)  // 30 -> 50.0 (TIES 20; inserted AFTER 20)
        m.addConflict(40, 70, 99, isEmptyBefore = false)  // 40 -> 70.0 (new nation, arsort)
        // DESC by value: 40(70), then the 50-tie {20 before 30 by insertion}, then 10(30).
        assertEquals(listOf(40, 20, 30, 10), m.keysInOrder())
    }

    @Test
    fun `arsort tie keeps insertion order at large map size (no pre-8 small-array divergence)`() {
        val m = ConflictMap()
        m.addConflict(1, 100, 99, isEmptyBefore = true) // anchor top
        // 30 nations all tying at value 10.0, inserted in id order 100..129.
        for (id in 100..129) {
            m.addConflict(id, 10, 99, isEmptyBefore = false)
        }
        val keys = m.keysInOrder()
        assertEquals(1, keys.first()) // 100.0 stays on top
        // The 30 tied nations keep their insertion order regardless of count (PHP 8.0 unconditional stable).
        assertEquals((100..129).toList(), keys.drop(1))
    }

    // --- getConquerNation = array_key_first of the (already-sorted) map ---------------------------------

    @Test
    fun `getConquerNation returns the first key (highest weight)`() {
        val m = ConflictMap()
        m.addConflict(5, 10, 30, isEmptyBefore = true)
        m.addConflict(8, 99, 30, isEmptyBefore = false) // larger → sorts to front
        assertEquals(8, m.getConquerNation())
    }

    @Test
    fun `getConquerNation on empty map is null`() {
        assertNull(ConflictMap().getConquerNation())
    }

    // --- deleteConflict: remove in place, NO re-sort -------------------------------------------------------

    @Test
    fun `deleteConflict removes a nation preserving the survivor order without re-sorting`() {
        val m = ConflictMap()
        m.addConflict(1, 70, 99, isEmptyBefore = true)
        m.addConflict(2, 50, 99, isEmptyBefore = false)
        m.addConflict(3, 30, 99, isEmptyBefore = false)
        // current order: [1(70), 2(50), 3(30)]
        m.deleteConflict(2)
        assertEquals(listOf(1, 3), m.keysInOrder()) // survivors keep their relative order, NOT re-sorted
        assertNull(m.weightOf(2))
    }

    @Test
    fun `deleteConflict of an absent nation is a no-op`() {
        val m = ConflictMap()
        m.addConflict(1, 10, 30, isEmptyBefore = true)
        m.deleteConflict(999)
        assertEquals(listOf(1), m.keysInOrder())
    }

    // --- encode/decode round-trip (insertion-ordered, integer-string keys) -------------------------------

    @Test
    fun `empty map encodes to PHP empty-object braces`() {
        assertEquals("{}", ConflictMap().encode())
    }

    @Test
    fun `decode then encode preserves insertion order and value forms`() {
        val m = ConflictMap.decode("""{"3":210,"1":50.5}""")
        assertEquals(listOf(3, 1), m.keysInOrder())
        assertEquals(210.0, m.weightOf(3))
        assertEquals(50.5, m.weightOf(1))
    }
}
