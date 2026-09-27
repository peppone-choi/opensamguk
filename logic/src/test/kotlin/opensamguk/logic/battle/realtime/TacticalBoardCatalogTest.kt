package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TacticalBoardCatalogTest {
    private val catalog by lazy { TacticalBoardCatalog.parse(payload()) }

    @Test
    fun `214 board catalog excludes nonland boards and picks deterministically from top ratio matches`() {
        assertEquals(214, catalog.boards.size)
        val province = TerrainProfile.fromTiles("P".repeat(100).asIterable())
        val choices = (0..50).map { battleId ->
            catalog.select("battle-$battleId", BattleKind.ENCOUNTER, province)
        }
        assertTrue(choices.all { it.board.battlefield.id in 188..192 })
        assertTrue(choices.map { it.board.battlefield.id }.toSet().size > 1)
        assertEquals(choices.first(), catalog.select("battle-0", BattleKind.ENCOUNTER, province))
        assertTrue(catalog.select("siege", BattleKind.SIEGE, province).board.battlefield.kind == "FORTRESS")
    }

    @Test
    fun `catalog rejects terrain mismatch and incomplete board list`() {
        assertFailsWith<IllegalArgumentException> {
            TacticalBoardCatalog.parse(payload().replaceFirst("\"P\":4095", "\"P\":4094"))
        }
        assertFailsWith<IllegalArgumentException> {
            TacticalBoardCatalog.parse(payload(213))
        }
    }

    private fun payload(count: Int = 214): String {
        val plain = "P".repeat(64)
        val wall = "P".repeat(10) + "W" + "P".repeat(53)
        val boards = (0 until count).joinToString(",") { id ->
            val fortress = id < 188
            val rows = (0 until 64).joinToString(",") { row -> "\"${if (fortress && row == 10) wall else plain}\"" }
            val counts = if (fortress) "\"P\":4095,\"F\":0,\"M\":0,\"R\":0,\"W\":1" else
                "\"P\":4096,\"F\":0,\"M\":0,\"R\":0,\"W\":0"
            """{"id":$id,"kind":"${if (fortress) "FORTRESS" else "FIELD"}","tileset":0,"image":"maps/battle_${id.toString().padStart(3, '0')}_ts0.png","landEligible":${id !in 209..212},"terrainCounts":{$counts},"terrainRows":[$rows]}"""
        }
        return """{"schemaVersion":1,"boardSize":64,"status":"owner-accepted derived catalog","source":{"originalBinaryCommitted":false},"boards":[$boards]}"""
    }
}
