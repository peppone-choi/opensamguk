package opensamguk.logic.auction

import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T0.7 — Auction DTO jsonb codec parity: NullIsUndefined (omit-null), JsonString (nested-as-string),
 * RawName column map, LinkedHashMap insertion order. The byte-faithful detail/aux jsonb is the gate.
 */
class AuctionDtoTest {

    @Test
    fun `AuctionInfoDetail omits null fields and preserves PHP property order`() {
        val d = AuctionInfoDetail(
            title = "쌀 100", hostName = "상인", amount = 100, isReverse = null,
            startBidAmount = 50, finishBidAmount = null, remainCloseDateExtensionCnt = null,
            availableLatestBidCloseDate = null,
        )
        // NullIsUndefined: isReverse/finishBidAmount/remainCloseDateExtensionCnt/availableLatestBidCloseDate dropped.
        assertEquals(listOf("title", "hostName", "amount", "startBidAmount"), d.toArray().keys.toList())
        assertEquals("""{"title":"쌀 100","hostName":"상인","amount":100,"startBidAmount":50}""", d.toJson())
    }

    @Test
    fun `AuctionInfoDetail includes present optional fields in property order`() {
        val d = AuctionInfoDetail(
            title = "t", hostName = "h", amount = 10, isReverse = true,
            startBidAmount = 5, finishBidAmount = 99, remainCloseDateExtensionCnt = 3,
            availableLatestBidCloseDate = "2026-05-31 01:00:00",
        )
        assertEquals(
            listOf("title", "hostName", "amount", "isReverse", "startBidAmount", "finishBidAmount", "remainCloseDateExtensionCnt", "availableLatestBidCloseDate"),
            d.toArray().keys.toList(),
        )
    }

    @Test
    fun `AuctionInfo RawName columns + detail as a JsonString + id omitted on INSERT`() {
        val info = AuctionInfo(
            id = null, type = AuctionType.UNIQUE_ITEM, finished = false, target = "che_보검",
            hostGeneralId = 0, reqResource = ResourceType.INHERITANCE_POINT,
            openDate = "2026-05-31 00:00:00", closeDate = "2026-05-31 01:00:00",
            detail = AuctionInfoDetail(title = "보검", hostName = "상인", amount = 5000, startBidAmount = 5000),
        )
        val cols = info.toArray()
        assertFalse("id" in cols, "NullIsUndefined: null id omitted on INSERT")
        assertEquals("uniqueItem", cols["type"])
        assertEquals("inheritPoint", cols["req_resource"])
        assertEquals(0, cols["host_general_id"])
        // detail is a JSON STRING (JsonString), not a nested map.
        assertTrue(cols["detail"] is String)
        assertEquals("""{"title":"보검","hostName":"상인","amount":5000,"startBidAmount":5000}""", cols["detail"])
        // toArray(withoutId=true) (PHP toArray('id')) omits id for the UPDATE-WHERE-id path.
        assertFalse("id" in info.copy(id = 7).toArray(withoutId = true))
    }

    @Test
    fun `AuctionInfo round-trips through fromArray (detail decoded from the json string)`() {
        val info = AuctionInfo(
            id = 7, type = AuctionType.BUY_RICE, finished = true, target = null,
            hostGeneralId = 42, reqResource = ResourceType.GOLD,
            openDate = "2026-05-31 00:00:00", closeDate = "2026-05-31 01:00:00",
            detail = AuctionInfoDetail(title = "t", hostName = "h", amount = 10, isReverse = false, startBidAmount = 5, finishBidAmount = 20),
        )
        val round = AuctionInfo.fromArray(info.toArray())
        assertEquals(info, round)
    }

    @Test
    fun `AuctionBidItem omits no on INSERT and aux is a JsonString`() {
        val bid = AuctionBidItem(
            no = null, auctionId = 7, owner = null, generalId = 42, amount = 5050, date = "2026-05-31 00:30:00",
            aux = AuctionBidItemData(ownerName = null, generalName = "관우", tryExtendCloseDate = true),
        )
        val cols = bid.toArray()
        assertFalse("no" in cols)
        assertNull(cols["owner"])
        assertTrue(cols["aux"] is String)
        // NullIsUndefined on ownerName: dropped; tryExtendCloseDate present.
        assertEquals("""{"generalName":"관우","tryExtendCloseDate":true}""", cols["aux"])
    }

    @Test
    fun `jsonEncode is PHP-faithful (compact, unescaped unicode and slashes)`() {
        assertEquals("""{"a":1,"b":"촉/한"}""", jsonEncode(linkedMapOf("a" to 1, "b" to "촉/한")))
        assertEquals(linkedMapOf<String, Any?>("a" to 1, "b" to "촉/한"), jsonDecode("""{"a":1,"b":"촉/한"}"""))
    }
}
