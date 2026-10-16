package opensamguk.logic.battle.realtime.space

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TacticalV2SpaceParamsTest {
    // Synthetic fixtures are not product decisions about capacity or radius.
    private val confirmed = """{"schemaVersion":1,"status":"CONFIRMED","cellCapacity":4,"soldierRadiusRaw":8192}"""

    @Test
    fun `packaged proposed resource is present but never activates parameters`() {
        val resource = requireNotNull(javaClass.classLoader.getResource("battle/soldier/space-v1.json"))
        assertEquals("""{
  "schemaVersion": 1,
  "status": "PROPOSED",
  "cellCapacity": null,
  "soldierRadiusRaw": null
}
""", resource.readText(Charsets.UTF_8))
        val result = assertIs<TacticalV2SpaceParamsLoad.NotDecided>(TacticalV2SpaceParams.load())
        assertTrue(result.reason.startsWith("NOT_DECIDED:"))
    }

    @Test
    fun `proposed populated values and other unconfirmed statuses cannot activate parameters`() {
        for (status in listOf("PROPOSED", "REJECTED", "confirmed", "")) {
            assertIs<TacticalV2SpaceParamsLoad.NotDecided>(
                TacticalV2SpaceParams.parse(confirmed.replace("CONFIRMED", status)))
        }
        for (status in listOf("null", "true", "1")) {
            assertIs<TacticalV2SpaceParamsLoad.NotDecided>(
                TacticalV2SpaceParams.parse(confirmed.replace("\"CONFIRMED\"", status)))
        }
        assertIs<TacticalV2SpaceParamsLoad.NotDecided>(
            TacticalV2SpaceParams.parse(confirmed.replace("\"status\":\"CONFIRMED\",", "")))
    }

    @Test
    fun `confirmed with either null or missing value remains undecided`() {
        for (field in listOf("\"cellCapacity\":4", "\"soldierRadiusRaw\":8192")) {
            assertIs<TacticalV2SpaceParamsLoad.NotDecided>(
                TacticalV2SpaceParams.parse(confirmed.replace(field, field.substringBefore(':') + ":null")))
            assertIs<TacticalV2SpaceParamsLoad.NotDecided>(
                TacticalV2SpaceParams.parse(confirmed.replace("," + field, "")))
        }
    }

    @Test
    fun `confirmed fixture pins exact input bytes without changing integer values`() {
        val result = assertIs<TacticalV2SpaceParamsLoad.Ready>(TacticalV2SpaceParams.parse(confirmed))
        assertEquals(TacticalV2SpaceParams(4, Fx(8192), "f372624a05cf220acb1ab8505dd2763102816ffb6d4a7db21f48aff2b08f7717"), result.params)
        assertNotEquals(result.params.sha256,
            assertIs<TacticalV2SpaceParamsLoad.Ready>(TacticalV2SpaceParams.parse(confirmed + "\n")).params.sha256)
    }

    @Test
    fun `malformed confirmed numeric inputs and unsupported schemas fail closed`() {
        val invalid = listOf(
            confirmed.replace("\"cellCapacity\":4", "\"cellCapacity\":0"),
            confirmed.replace("\"cellCapacity\":4", "\"cellCapacity\":-1"),
            confirmed.replace("\"cellCapacity\":4", "\"cellCapacity\":2147483648"),
            confirmed.replace("\"cellCapacity\":4", "\"cellCapacity\":1.5"),
            confirmed.replace("\"cellCapacity\":4", "\"cellCapacity\":\"4\""),
            confirmed.replace("8192", "0"),
            confirmed.replace("8192", "-1"),
            confirmed.replace("8192", "9223372036854775808"),
            confirmed.replace("8192", "1.5"),
            confirmed.replace("8192", "\"8192\""),
            confirmed.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            confirmed.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            "not json",
        )
        for (payload in invalid) {
            assertFailsWith<IllegalArgumentException>(payload) { TacticalV2SpaceParams.parse(payload) }
        }
    }

    @Test
    fun `fixed point uses long precision and preserves row col order for direct fixtures`() {
        assertEquals(16, Fx.SHIFT)
        assertEquals(65536L, Fx.ONE)
        assertTrue(Fx(Long.MIN_VALUE) < Fx(Long.MAX_VALUE))
        val position = TacticalV2Pos(Fx(3 * Fx.ONE + 1), Fx(5 * Fx.ONE + 2))
        assertEquals(3L, position.row.raw shr Fx.SHIFT)
        assertEquals(5L, position.col.raw shr Fx.SHIFT)
        val fixture = TacticalV2SpaceParams(4, Fx(8192), "a".repeat(64))
        assertEquals(Fx(8192), fixture.soldierRadius)
    }
}
