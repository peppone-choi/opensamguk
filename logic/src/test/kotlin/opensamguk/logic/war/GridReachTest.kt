package opensamguk.logic.war

import kotlin.test.*
import opensamguk.logic.world.*
import opensamguk.logic.world.BattlefieldGeometry.Position

class GridReachTest {
    private fun layout(mountain: Set<Position> = emptySet(), holes: Set<Position> = emptySet()): BattlefieldLayout {
        val cells = (0..4).flatMap { row -> (0..4).mapNotNull { col ->
            val p = Position(col,row)
            if (p in holes) null else HanProvinceCell(col+1,row,if(p in mountain) '2' else '1')
        } }
        val index = HanProvinceCellIndex("qa","a".repeat(64),"b".repeat(64),6,5,
            mapOf('1' to "PLAIN",'2' to "MOUNTAIN"),mapOf("a" to listOf(HanProvinceCell(0,0,'1')),"b" to cells))
        return (BattlefieldLayout.prepare(index,"b","a") as BattlefieldLayout.Result.Ready).layout
    }
    @Test fun `adjacency range and diagonal distance use Manhattan metric`() {
        val l=layout()
        assertTrue(GridReach.canStrike(l,Position(0,0),Position(1,0),1))
        assertFalse(GridReach.canStrike(l,Position(0,0),Position(1,1),1))
        assertTrue(GridReach.canStrike(l,Position(0,0),Position(1,1),2))
        assertFalse(GridReach.canStrike(l,Position(0,0),Position(0,0),3))
        assertFalse(GridReach.canStrike(l,Position(0,0),Position(4,0),3))
    }
    @Test fun `mountains holes and corner contact block even when endpoints connected by detour`() {
        for(l in listOf(layout(mountain=setOf(Position(1,0))),layout(holes=setOf(Position(1,0))))) {
            assertFalse(GridReach.canStrike(l,Position(0,0),Position(2,0),4))
            assertFalse(GridReach.canStrike(l,Position(0,0),Position(1,1),4))
        }
        val divided=layout(mountain=(0..4).map { Position(2,it) }.toSet())
        assertFalse(GridReach.canStrike(divided,Position(0,0),Position(4,0),9))
        assertFalse(GridReach.canStrike(layout(mountain=setOf(Position(0,1))),Position(0,0),Position(1,1),2))
    }
    @Test fun `all endpoint pairs are symmetric including uneven slopes and obstacles`() {
        val l=layout(mountain=setOf(Position(2,2)),holes=setOf(Position(3,1)))
        for(a in l.distancesFromEntry.keys) for(b in l.distancesFromEntry.keys)
            assertEquals(GridReach.canStrike(l,a,b,9),GridReach.canStrike(l,b,a,9),"$a $b")
        assertTrue(GridReach.canStrike(layout(),Position(0,0),Position(4,2),6))
    }
    @Test fun `invalid range throws and extreme coordinates reject before arithmetic`() {
        val l=layout()
        for(r in listOf(0,-1,Int.MIN_VALUE)) assertFailsWith<IllegalArgumentException> {
            GridReach.canStrike(l,Position(0,0),Position(1,0),r)
        }
        for(p in listOf(Position(Int.MAX_VALUE,Int.MIN_VALUE),Position(-1,0),Position(5,0))) {
            assertFalse(GridReach.canStrike(l,p,Position(0,0),Int.MAX_VALUE))
            assertFalse(GridReach.canStrike(l,Position(0,0),p,Int.MAX_VALUE))
        }
    }
}
