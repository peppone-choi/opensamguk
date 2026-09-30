package opensamguk.logic.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import opensamguk.logic.input.RoadFort

class RoadFortEventRefTest {
    @Test fun `canonical road fort site id survives the typed ref codec`() {
        val siteId = RoadFort.siteId("land-boundary:5:9512612:gc-g0079-001", 1195, 1837)
        val refs = mapOf(RefRole.ROAD_FORT to EventRef.RoadFort(siteId))

        assertEquals(refs, EventPayloadCodec.decodeRefs(EventPayloadCodec.encodeRefs(refs)))
    }

    @Test fun `malformed road fort site id is rejected`() {
        for (id in listOf(
            "road-piece@-1,0", "road-piece@+1,0", "road-piece@01,0", "road-piece@0,00",
            "road-piece@2147483648,0", "road-piece@0,2147483648", "road-piece@1.0,0",
            "road-piece@1,", "road-piece@,1", "road-piece@@1,0", "road-piece@1,0,2",
            "road-piece@1,0@", "road-piece @1,0", "road-piece@1, 0", "road-piece@1,0\n",
            "road-piece@1,0' OR 1=1", "${"e".repeat(129)}@0,0",
        )) {
            assertFailsWith<IllegalArgumentException>(id) { EventRef.RoadFort(id) }
        }
    }

    @Test fun `legacy stable id and maximum canonical components remain accepted`() {
        val edge = "e".repeat(128)
        val site = RoadFort.siteId(edge, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(edge, EventRef.RoadFort(edge).id)
        assertEquals(site, EventRef.RoadFort(site).id)
        assertEquals(true, site.length > 128)
        assertFailsWith<IllegalArgumentException> { EventRef.RoadFort("e".repeat(129)) }
    }

    @Test fun `other refs and event key keep their original grammar`() {
        val site = RoadFort.siteId("road-piece", 1, 2)
        assertFailsWith<IllegalArgumentException> { EventRef.Corps(site) }
        assertFailsWith<IllegalArgumentException> { EventRef.Replay(site) }
        assertFailsWith<IllegalArgumentException> { EventRef.Request(site) }
        assertFailsWith<IllegalArgumentException> { EventKey.derive(site) }
    }
}
