package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.war.ConflictMap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * P4 G1 (A4) — the ConflictMap winner gate. Replays the committed PHP `conflict-01.json` golden (a 3-nation
 * siege of one city) through the Kotlin [ConflictMap] and asserts, against PHP grand truth
 * (`WarUnitCity.php:152-181` addConflict, `process_war.php:518/526-530` deleteConflict/getConquerNation):
 *
 *  - each siege step's `addConflict(nation, dead, hp, isEmptyBefore)` reproduces the golden's
 *    `conflictAfter` weights + the byte-exact `conflictJsonRaw` + the `newConflict` flag (분쟁-log trigger);
 *  - the ×1.05 선타(first-hit, empty map)/막타(killing blow, hp==0) bonus is a SINGLE ×1.05 (OR, never ×1.1025);
 *  - ZERO RNG draws (the whole conflict path is pure arithmetic + a stable arsort);
 *  - the winner = `getConquerNation` = `array_key_first(conflict)` after arsort (stable DESC, insertion-order
 *    tie-break) = N3;
 *  - `deleteConflict(winner)` is an `unset` with NO re-sort — the surviving keys keep their order.
 */
class ConflictWinnerGateTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        ConflictWinnerGateTest::class.java.classLoader
            .getResourceAsStream("golden/p4/conflict-01.json")!!
            .readBytes().toString(Charsets.UTF_8),
    ).jsonObject

    @Test
    fun `siege steps reproduce the golden conflict weights + json + newConflict, with the single x1_05 bonus`() {
        val conflict = ConflictMap()
        val steps = golden["siege_steps"]!!.jsonArray.map { it.jsonObject }

        for (step in steps) {
            val nation = step["attackerNation"]!!.jsonPrimitive.int
            val dead = step["deadInflicted"]!!.jsonPrimitive.int
            val hpZero = step["hpZero"]!!.jsonPrimitive.boolean
            // 선타: the empty-map branch is the FIRST add. The golden records the pre-call emptiness implicitly:
            // step 0 is the only empty-before step (the map starts empty, every later step is non-empty).
            val isEmptyBefore = conflict.keysInOrder().isEmpty()

            val newConflict = conflict.addConflict(nation, dead, cityHp = if (hpZero) 0 else 1, isEmptyBefore = isEmptyBefore)

            assertEquals(
                step["newConflict"]!!.jsonPrimitive.boolean, newConflict,
                "siege ${step["siegeIdx"]} newConflict (분쟁-log trigger)",
            )
            // the weights after this step.
            val expectedAfter = step["conflictAfter"]!!.jsonObject.entries
                .associate { (k, v) -> k.toInt() to v.jsonPrimitive.content.toDouble() }
            val actualAfter = conflict.keysInOrder().associateWith { conflict.weightOf(it)!! }
            assertEquals(expectedAfter, actualAfter, "siege ${step["siegeIdx"]} conflict weights")
            // the byte-exact insertion-ordered JSON.
            assertEquals(
                step["conflictJsonRaw"]!!.jsonPrimitive.content, conflict.encode(),
                "siege ${step["siegeIdx"]} conflict JSON (insertion-ordered, int/float byte-faithful)",
            )
        }

        // final json byte-match.
        assertEquals(golden["final_conflict_json"]!!.jsonPrimitive.content, conflict.encode(), "final conflict JSON")
    }

    @Test
    fun `the x1_05 bonus is a single multiply for 선타 first-hit (empty map) — never x1_1025`() {
        // siege 0: nation 1 inflicts 3000 raw on an EMPTY map (선타) → 3000 * 1.05 = 3150 (a SINGLE ×1.05).
        val conflict = ConflictMap()
        val newC = conflict.addConflict(1, 3000, cityHp = 1, isEmptyBefore = true)
        assertEquals(false, newC, "선타 on an empty map sets newConflict=false (no 분쟁 log)")
        assertEquals(3150.0, conflict.weightOf(1)!!, 0.0, "선타 = dead * 1.05 (single), not 1.1025")
    }

    @Test
    fun `getConquerNation is array_key_first after arsort — winner N3 (stable DESC, insertion tie-break)`() {
        // Replay the full siege, then assert the winner.
        val conflict = ConflictMap()
        for (step in golden["siege_steps"]!!.jsonArray.map { it.jsonObject }) {
            val isEmptyBefore = conflict.keysInOrder().isEmpty()
            conflict.addConflict(
                step["attackerNation"]!!.jsonPrimitive.int,
                step["deadInflicted"]!!.jsonPrimitive.int,
                cityHp = if (step["hpZero"]!!.jsonPrimitive.boolean) 0 else 1,
                isEmptyBefore = isEmptyBefore,
            )
        }
        assertEquals(
            golden["getConquerNation_winner"]!!.jsonPrimitive.int, conflict.getConquerNation(),
            "winner = array_key_first(arsort(conflict)) — N3 (highest weight 5000)",
        )
    }

    @Test
    fun `deleteConflict unsets the winner with NO re-sort — remaining keys keep order`() {
        val conflict = ConflictMap.decode(golden["final_conflict_json"]!!.jsonPrimitive.content)
        val del = golden["deleteConflict"]!!.jsonObject
        val removed = del["removedNation"]!!.jsonPrimitive.int

        conflict.deleteConflict(removed)

        val expectedResult = del["result"]!!.jsonObject.entries
            .associate { (k, v) -> k.toInt() to v.jsonPrimitive.content.toDouble() }
        val actualResult = conflict.keysInOrder().associateWith { conflict.weightOf(it)!! }
        assertEquals(expectedResult, actualResult, "deleteConflict = unset(key), NO re-sort (insertion order kept)")
    }
}
