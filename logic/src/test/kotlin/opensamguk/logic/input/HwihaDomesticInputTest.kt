package opensamguk.logic.input

import kotlin.test.*

class HwihaDomesticInputTest {
    @Test fun `placement bodies need the exact keys of their post`() {
        assertEquals(PlacementRequest(7, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(10)),
            HwihaDomesticInput.parsePlacement(7, """{"cardId":4,"post":"MAGISTRATE","countyId":10}"""))
        assertEquals(PlacementRequest(7, 4, PlacementPost.SCOUT, PlacementTarget.Province("70623")),
            HwihaDomesticInput.parsePlacement(7, """{"post":"SCOUT","cardId":4,"provinceId":"70623"}"""))
        assertEquals(PlacementRequest(7, 4, PlacementPost.ENVOY, PlacementTarget.Nation(3)),
            HwihaDomesticInput.parsePlacement(7, """{"cardId":4,"post":"ENVOY","nationId":3}"""))
        assertEquals(PlacementRequest(7, 4, PlacementPost.CORPS_COMMANDER, PlacementTarget.None),
            HwihaDomesticInput.parsePlacement(7, """{"cardId":4,"post":"CORPS_COMMANDER"}"""))
        assertEquals(PlacementRequest(7, 4, PlacementPost.NONE, PlacementTarget.None),
            HwihaDomesticInput.parsePlacement(7, """{"cardId":4,"post":"NONE"}"""))
        val bad = listOf(
            """{"cardId":4,"post":"MAGISTRATE"}""", """{"cardId":4,"post":"MAGISTRATE","countyId":10,"x":1}""",
            """{"cardId":4,"post":"MAGISTRATE","provinceId":"1"}""", """{"cardId":"4","post":"MAGISTRATE","countyId":10}""",
            """{"cardId":4,"post":"MAGISTRATE","countyId":10.0}""", """{"cardId":4,"post":"MAGISTRATE","countyId":0}""",
            """{"cardId":4,"post":"MAGISTRATE","countyId":99999999999}""", """{"cardId":4,"cardId":5,"post":"NONE"}""",
            """{"cardId":4,"cardId":5,"post":"NONE"}""", """{"cardId":4,"post":"magistrate","countyId":1}""",
            """{"cardId":4,"post":"SCOUT","provinceId":"a b"}""", """{"cardId":4,"post":"NONE","countyId":1}""",
            """{"cardId":4,"post":"GENERAL"}""", """{"cardId":4,"post":"NONE"} x""", """[]""", "",
        )
        for (raw in bad) assertNull(HwihaDomesticInput.parsePlacement(7, raw), raw)
        assertNull(HwihaDomesticInput.parsePlacement(0, """{"cardId":4,"post":"NONE"}"""))
        assertNull(HwihaDomesticInput.parsePlacement(7, null))
    }

    @Test fun `policy bodies are scoped and NONE clears`() {
        assertEquals(PolicyRequest(7, PolicyTarget.County(10), "AGRICULTURE"),
            HwihaDomesticInput.parsePolicy(7, """{"scope":"COUNTY","countyId":10,"policy":"AGRICULTURE"}"""))
        assertEquals(PolicyRequest(7, PolicyTarget.Commandery("京兆尹"), null),
            HwihaDomesticInput.parsePolicy(7, """{"scope":"COMMANDERY","commanderyId":"京兆尹","policy":"NONE"}"""))
        assertEquals(PolicyRequest(7, PolicyTarget.Corps("order-1"), "INTERCEPT"),
            HwihaDomesticInput.parsePolicy(7, """{"scope":"CORPS","orderId":"order-1","policy":"INTERCEPT"}"""))
        val bad = listOf(
            """{"scope":"COUNTY","countyId":10,"policy":"INTERCEPT"}""", """{"scope":"CORPS","orderId":"o","policy":"AGRICULTURE"}""",
            """{"scope":"COUNTY","commanderyId":"x","policy":"AGRICULTURE"}""", """{"scope":"NATION","countyId":1,"policy":"AGRICULTURE"}""",
            """{"scope":"COUNTY","countyId":10}""", """{"scope":"COUNTY","countyId":10,"policy":null}""",
            """{"scope":"COMMANDERY","commanderyId":"a b","policy":"AGRICULTURE"}""",
            """{"scope":"CORPS","orderId":"bad id","policy":"EVADE"}""",
        )
        for (raw in bad) assertNull(HwihaDomesticInput.parsePolicy(7, raw), raw)
    }

    @Test fun `work bodies name one of the eleven works`() {
        assertEquals(WorkRequest(7, 10, DomesticWork.WATCHTOWER_BEACON),
            HwihaDomesticInput.parseWork(7, """{"countyId":10,"work":"WATCHTOWER_BEACON"}"""))
        assertNull(HwihaDomesticInput.parseWork(7, """{"countyId":10,"work":"망루봉화"}"""))
        assertNull(HwihaDomesticInput.parseWork(7, """{"countyId":10,"work":"IRRIGATION","extra":1}"""))
        assertEquals(11, DomesticWork.entries.size)
        assertEquals(listOf("수리", "둔전", "성방", "도로", "역참", "창고", "곡창", "망루봉화", "성벽관문", "병영", "시장수운"),
            DomesticWork.entries.map { it.label })
        assertEquals(listOf("권농", "중상", "중세", "휼민", "둔전", "징발"), CountyPolicy.entries.map { it.label })
        assertEquals(listOf("조련", "공략", "수비", "요격", "회피"), CorpsPolicy.entries.map { it.label })
    }

    @Test fun `canonical json round trips and is key ordered`() {
        val placement = PlacementRequest(7, 4, PlacementPost.SCOUT, PlacementTarget.Province("p1"))
        assertEquals("""{"cardId":4,"post":"SCOUT","provinceId":"p1"}""", HwihaDomesticInput.canonicalJson(placement))
        assertEquals(placement, HwihaDomesticInput.parsePlacement(7, HwihaDomesticInput.canonicalJson(placement)))
        val policy = PolicyRequest(7, PolicyTarget.County(3), null)
        assertEquals("""{"scope":"COUNTY","countyId":3,"policy":"NONE"}""", HwihaDomesticInput.canonicalJson(policy))
        assertEquals(policy, HwihaDomesticInput.parsePolicy(7, HwihaDomesticInput.canonicalJson(policy)))
        val work = WorkRequest(7, 3, DomesticWork.ROAD)
        assertEquals(work, HwihaDomesticInput.parseWork(7, HwihaDomesticInput.canonicalJson(work)))
    }
}
