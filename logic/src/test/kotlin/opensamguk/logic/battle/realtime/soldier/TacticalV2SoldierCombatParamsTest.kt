package opensamguk.logic.battle.realtime.soldier

import kotlin.test.*

class TacticalV2SoldierCombatParamsTest {
    private val confirmed = """{"schemaVersion":1,"status":"CONFIRMED","hitPoints":8,"damage":2}"""

    @Test
    fun `packaged proposed data does not activate combat`() {
        assertIs<TacticalV2SoldierCombatParamsLoad.NotDecided>(TacticalV2SoldierCombatParams.load())
        assertIs<TacticalV2SoldierCombatParamsLoad.NotDecided>(TacticalV2SoldierCombatParams.parse(
            confirmed.replace("CONFIRMED", "PROPOSED")))
    }

    @Test
    fun `null missing and unknown status stay undecided`() {
        for (payload in listOf(confirmed.replace("\"hitPoints\":8", "\"hitPoints\":null"),
            confirmed.replace(",\"damage\":2", ""), confirmed.replace("CONFIRMED", "confirmed"))) {
            assertIs<TacticalV2SoldierCombatParamsLoad.NotDecided>(TacticalV2SoldierCombatParams.parse(payload))
        }
    }

    @Test
    fun `confirmed fixture pins bytes and rejects malformed positive integers`() {
        val ready = assertIs<TacticalV2SoldierCombatParamsLoad.Ready>(TacticalV2SoldierCombatParams.parse(confirmed))
        assertEquals(8, ready.params.hitPoints)
        assertEquals(soldierSha(confirmed.toByteArray()), ready.params.sha256)
        for (value in listOf("0", "-1", "1.5", "\"8\"", "2147483648")) {
            assertFailsWith<IllegalArgumentException> {
                TacticalV2SoldierCombatParams.parse(confirmed.replace("\"hitPoints\":8", "\"hitPoints\":$value"))
            }
        }
    }
}
