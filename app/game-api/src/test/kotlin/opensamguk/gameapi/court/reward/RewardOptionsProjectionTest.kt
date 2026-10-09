package opensamguk.gameapi.court.reward

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources

class RewardOptionsProjectionTest {
    private val projection = RewardOptionsProjection()
    private fun city(id: Int, money: Long, nation: Int = 1, supplied: Boolean = true, revision: Long = 3) =
        RewardCity(id, nation, supplied, mapOf(CountyWarehouse.META_KEY to
            CountyWarehouse(id, revision, Resources(money = money)).toMetaValue()))
    private fun snapshot(loyalty: Int = 0, money: Long = 10_000) = RewardReadSnapshot(1, "READY",
        date = RewardSnapshotDto(200, 1, 2), payerNationId = 1, payerNationPresent = true, capitalCityId = 2,
        cards = listOf(RewardCard(7, 11, loyalty)), people = mapOf(11 to RewardPerson(11, "수신 인물", 1)),
        cities = listOf(city(1, money), city(2, money)), countyIds = setOf(1, 2),
        queued = RewardQueuedDto("NONE", null, null))

    @Test fun `every verdict follows the exact effect funding debit and explicit null table`() {
        val read = snapshot(95).copy(cities = listOf(city(1, 200), city(2, 30)))
        val unchecked = listOf("QUEUE_ADMISSION", "REWARD_HISTORY", "CONCURRENT_DEBITS", "STATE_AFTER_SNAPSHOT")
        val plan = listOf(RewardDebitDto(2, true, "30", "30", "3"), RewardDebitDto(1, false, "120", "200", "3"))
        val cases = listOf(
            Triple(RewardPreviewDto(7, null, "INVALID_AMOUNT", null, null, null, null, null, unchecked), read, "0"),
            Triple(RewardPreviewDto(999, 150, "CARD_UNAVAILABLE", null, null, null, null, null, unchecked), read, "150"),
            Triple(RewardPreviewDto(7, null, "NO_AMOUNT", null, null, null, null, null, unchecked), read, null),
            Triple(RewardPreviewDto(7, 99, "TOO_SMALL", null, null, null, null, null, unchecked), read, "99"),
            Triple(RewardPreviewDto(7, 501, "REWARD_OVER_CAP", null, null, null, null, null, unchecked), read, "501"),
            Triple(RewardPreviewDto(7, 150, "FUNDING_UNAVAILABLE", 1, 96, 50, null, null, unchecked),
                read.copy(payerNationPresent = false), "150"),
            Triple(RewardPreviewDto(7, 150, "INSUFFICIENT_STOCK", 1, 96, 50, "50", null, unchecked),
                read.copy(cities = listOf(city(1, 20), city(2, 30))), "150"),
            Triple(RewardPreviewDto(7, 150, "COVERED_AT_SNAPSHOT", 1, 96, 50, "230", plan, unchecked), read, "150"),
        )
        val mapper = ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val keys = setOf("retainerId", "money", "verdict", "loyaltyGain", "loyaltyAfter", "moneyWithoutGain",
            "usableMoney", "debitPlan", "notChecked")
        for ((expected, state, raw) in cases) {
            val actual = projection.project(state, expected.retainerId, raw).preview!!
            assertEquals(expected, actual, expected.verdict)
            val json = mapper.readTree(mapper.writeValueAsString(actual))
            assertEquals(keys, json.fieldNames().asSequence().toSet(), expected.verdict)
            assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), json, expected.verdict)
        }
    }

    @Test fun `invalid amount precedes unavailable cards and omission distinguishes unavailable from owned`() {
        val read = snapshot()
        for (state in listOf(read, read.copy(people = emptyMap()))) {
            for (card in listOf(7, 999)) {
                val invalid = projection.project(state, card, "invalid").preview!!
                assertEquals("INVALID_AMOUNT", invalid.verdict)
                assertEquals(card, invalid.retainerId)
                assertNull(invalid.money)
                assertNull(invalid.usableMoney)
            }
        }
        assertEquals("CARD_UNAVAILABLE", projection.project(read, 999, null).preview!!.verdict)
        assertEquals("NO_AMOUNT", projection.project(read, 7, null).preview!!.verdict)
        assertEquals("CARD_UNAVAILABLE", projection.project(read.copy(people = emptyMap()), 7, null).preview!!.verdict)
    }

    @Test fun `loyalty room is bounded per reward and nonpositive locations are explicitly null`() {
        for ((loyalty, room) in listOf(0 to 10, 90 to 10, 95 to 5, 99 to 1, 100 to 0))
            assertEquals(room, projection.project(snapshot(loyalty), null, null).cards!!.single().loyaltyRoom)
        for (location in listOf(0, -1)) {
            val read = snapshot().copy(people = mapOf(11 to RewardPerson(11, "Person", location)))
            val card = projection.project(read, null, null).cards!!.single()
            assertNull(card.locationCityId)
            assertEquals(RewardFundingDto("UNAVAILABLE", null, null, "LOCATION_UNKNOWN", null, null), card.funding)
        }
    }

    @Test fun `known nonadministrative locations preserve network and isolated zero semantics`() {
        for (supplied in listOf(true, false)) {
            val read = snapshot(money = 100).let { state -> state.copy(
                people = mapOf(11 to RewardPerson(11, "Person", 3)),
                cities = state.cities + city(3, 1_000_000, supplied = supplied).copy(
                    meta = mapOf(CountyWarehouse.META_KEY to "ignored noncounty warehouse"))) }
            val result = projection.project(read, 7, "100")
            assertEquals(3, result.cards!!.single().locationCityId)
            assertEquals(RewardFundingDto("KNOWN", if (supplied) "NETWORK" else "ISOLATED", null, null,
                if (supplied) "200" else "0", if (supplied) 2 else 0), result.cards.single().funding)
            assertEquals(if (supplied) "COVERED_AT_SNAPSHOT" else "INSUFFICIENT_STOCK", result.preview!!.verdict)
        }
    }

    @Test fun `every nonready root discards snapshot queued and all partial projection fields`() {
        for (status in listOf("UNAVAILABLE", "WRONG_RULE_PROFILE", "UNSUPPORTED_WORLD_FORMAT")) {
            val result = projection.project(snapshot().copy(status = status, reason = "closed"), 7, "100")
            assertEquals(status, result.status)
            assertNull(result.snapshot)
            assertNull(result.rule)
            assertNull(result.queued)
            assertNull(result.cards)
            assertNull(result.preview)
        }
        assertNull(projection.project(snapshot(), null, null).preview)
    }

    @Test fun `every unavailable funding reason has null scope totals and warehouse count`() {
        val read = snapshot()
        val cases = listOf(
            "RECIPIENT_MISSING" to read.copy(people = emptyMap()),
            "LOCATION_UNKNOWN" to read.copy(cities = emptyList()),
            "PAYER_NATION_MISSING" to read.copy(payerNationPresent = false),
            "WAREHOUSE_MALFORMED" to read.copy(cities = listOf(city(1, 100), city(2, 100).copy(
                meta = mapOf(CountyWarehouse.META_KEY to "malformed")))),
            "TOTAL_OVERFLOW" to read.copy(cities = listOf(city(1, Long.MAX_VALUE), city(2, 1))),
        )
        for ((reason, state) in cases)
            assertEquals(RewardFundingDto("UNAVAILABLE", null, null, reason, null, null),
                projection.project(state, null, null).cards!!.single().funding)
    }

    @Test fun `loyalty table preserves caps rounding waste and the loyalty 100 receipt`() {
        for ((loyalty, cap) in listOf(0 to 1000L, 95 to 500L, 99 to 100L, 100 to 100L)) {
            val hundred = projection.project(snapshot(loyalty), 7, "100")
            assertEquals(cap, hundred.cards!!.single().maximumMoney)
            val preview = hundred.preview!!
            assertEquals("COVERED_AT_SNAPSHOT", preview.verdict)
            assertEquals(if (loyalty == 100) 0 else 1, preview.loyaltyGain)
            assertEquals(minOf(100, loyalty + 1), preview.loyaltyAfter)
            assertEquals(if (loyalty == 100) 100L else 0L, preview.moneyWithoutGain)
            val partial = projection.project(snapshot(loyalty), 7, "150").preview!!
            assertEquals(if (loyalty >= 99) "REWARD_OVER_CAP" else "COVERED_AT_SNAPSHOT", partial.verdict)
            if (loyalty < 99) { assertEquals(1, partial.loyaltyGain); assertEquals(50L, partial.moneyWithoutGain) }
            else assertNull(partial.debitPlan)
            assertEquals(listOf("QUEUE_ADMISSION", "REWARD_HISTORY", "CONCURRENT_DEBITS", "STATE_AFTER_SNAPSHOT"),
                preview.notChecked)
        }
    }

    @Test fun `input syntax remains strict and never admits lossy or unbounded amounts`() {
        for (raw in listOf("0", "-1", "+100", "01", "100.0", "1e2", " 100", "1000000001", "9223372036854775807")) {
            val preview = projection.project(snapshot(), 7, raw).preview!!
            assertEquals("INVALID_AMOUNT", preview.verdict, raw)
            assertNull(preview.money)
            assertNull(preview.debitPlan)
        }
        assertEquals("NO_AMOUNT", projection.project(snapshot(), 7, null).preview!!.verdict)
        assertEquals("TOO_SMALL", projection.project(snapshot(), 7, "99").preview!!.verdict)
    }

    @Test fun `capital first plan is all or nothing and large balances revisions remain strings`() {
        val huge = 9_007_199_254_740_993L
        val read = snapshot().copy(cities = listOf(city(1, huge, revision = huge), city(2, 30)), capitalCityId = 2)
        val result = projection.project(read, 7, "100")
        assertEquals((huge + 30).toString(), result.cards!!.single().funding.usableMoney)
        assertEquals(listOf(RewardDebitDto(2, true, "30", "30", "3"),
            RewardDebitDto(1, false, "70", huge.toString(), huge.toString())), result.preview!!.debitPlan)
        val poor = snapshot().copy(cities = listOf(city(1, 20), city(2, 30)))
        assertEquals("INSUFFICIENT_STOCK", projection.project(poor, 7, "100").preview!!.verdict)
        assertNull(projection.project(poor, 7, "100").preview!!.debitPlan)
    }

    @Test fun `missing warehouse differs from malformed potential candidates and overflow`() {
        val read = snapshot()
        val absent = read.copy(cities = read.cities.map { it.copy(meta = emptyMap()) })
        val known = projection.project(absent, 7, "100").cards!!.single().funding
        assertEquals(RewardFundingDto("KNOWN", "NETWORK", null, null, "0", 0), known)
        for (bad in listOf(null, "bad", CountyWarehouse(2, 0, Resources()).toMetaValue() + ("revision" to 1.0))) {
            val malformed = read.copy(cities = listOf(city(1, 100), city(2, 100).copy(
                meta = mapOf(CountyWarehouse.META_KEY to bad))))
            assertEquals("WAREHOUSE_MALFORMED", projection.project(malformed, 7, "100")
                .cards!!.single().funding.unavailableReason)
            assertEquals("FUNDING_UNAVAILABLE", projection.project(malformed, 7, "100").preview!!.verdict)
        }
        val overflow = read.copy(cities = listOf(city(1, Long.MAX_VALUE), city(2, 1)))
        val funding = projection.project(overflow, 7, "100").cards!!.single().funding
        assertEquals("TOTAL_OVERFLOW", funding.unavailableReason)
        assertNull(funding.usableMoney)
    }

    @Test fun `isolated foreign neutral landless and missing recipients keep distinct funding semantics`() {
        val read = snapshot()
        val isolated = read.copy(cities = listOf(city(1, 100, supplied = false), city(2, 10_000)))
        assertEquals(RewardFundingDto("KNOWN", "ISOLATED", null, null, "100", 1),
            projection.project(isolated, 7, "100").cards!!.single().funding)
        for ((changed, reason) in listOf(read.copy(payerNationId = 0) to "PAYER_LANDLESS",
            read.copy(cities = listOf(city(1, 1, nation = 0))) to "LOCATION_NEUTRAL",
            read.copy(cities = listOf(city(1, 1, nation = 2))) to "LOCATION_FOREIGN")) {
            assertEquals(RewardFundingDto("KNOWN", "NONE", reason, null, "0", 0),
                projection.project(changed, 7, "100").cards!!.single().funding)
        }
        val missing = projection.project(read.copy(people = emptyMap()), 7, "100")
        assertNull(missing.cards!!.single().name)
        assertNull(missing.cards.single().locationCityId)
        assertEquals("RECIPIENT_MISSING", missing.cards.single().funding.unavailableReason)
        assertEquals("CARD_UNAVAILABLE", missing.preview!!.verdict)
        assertEquals("LOCATION_UNKNOWN", projection.project(read.copy(cities = emptyList()), 7, "100")
            .cards!!.single().funding.unavailableReason)
        assertEquals("PAYER_NATION_MISSING", projection.project(read.copy(payerNationPresent = false), 7, "100")
            .cards!!.single().funding.unavailableReason)
    }

    @Test fun `no cards is ready empty while unavailable never exposes a partial projection`() {
        val empty = projection.project(snapshot().copy(cards = emptyList()), 99, "100")
        assertEquals(emptyList(), empty.cards)
        assertEquals("CARD_UNAVAILABLE", empty.preview!!.verdict)
        assertNull(empty.preview.usableMoney)
        val closed = projection.project(snapshot().copy(status = "UNAVAILABLE", reason = "ROSTER_INVALID"), 7, "100")
        assertNull(closed.cards)
        assertNull(closed.preview)
    }
}
