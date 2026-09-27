package opensamguk.logic.battle.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TacticalBoardCatalogTest {
    private val catalog by lazy { TacticalBoardCatalog.parse(payload()) }

    @Test
    fun `214 board catalog excludes closest nonland boards and picks deterministically`() {
        assertEquals(214, catalog.boards.size)
        val province = TerrainProfile.fromTiles("F".repeat(100).asIterable())
        val choices = (0..50).map { battleId ->
            catalog.select("battle-$battleId", BattleKind.ENCOUNTER, province)
        }
        assertTrue(choices.none { it.board.battlefield.id in 209..212 })
        assertTrue(choices.all { it.board.battlefield.id in 200..204 })
        assertTrue(choices.map { it.board.battlefield.id }.toSet().size > 1)
        assertEquals(choices.first(), catalog.select("battle-0", BattleKind.ENCOUNTER, province))
        assertTrue(catalog.select("siege", BattleKind.SIEGE, province).board.battlefield.kind == "FORTRESS")
    }

    @Test
    fun `terrain ratio outranks board id for field candidates`() {
        val forestProvince = TerrainProfile.fromTiles("F".repeat(100).asIterable())
        val choices = (0..50).map { catalog.select("ratio-$it", BattleKind.ENCOUNTER, forestProvince) }
        assertTrue(choices.all { it.board.battlefield.id in 200..204 })
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
        val forest = "F".repeat(64)
        val wall = "P".repeat(10) + "W" + "P".repeat(53)
        val boards = (0 until count).joinToString(",") { id ->
            val fortress = id < 188
            val rows = (0 until 64).joinToString(",") { row ->
                val tiles = when {
                    fortress && row == 10 -> wall
                    id in 209..212 || id in 200..204 && row < 48 -> forest
                    else -> plain
                }
                "\"$tiles\""
            }
            val counts = when {
                fortress -> "\"P\":4095,\"F\":0,\"M\":0,\"R\":0,\"W\":1"
                id in 209..212 -> "\"P\":0,\"F\":4096,\"M\":0,\"R\":0,\"W\":0"
                id in 200..204 -> "\"P\":1024,\"F\":3072,\"M\":0,\"R\":0,\"W\":0"
                else -> "\"P\":4096,\"F\":0,\"M\":0,\"R\":0,\"W\":0"
            }
            """{"id":$id,"kind":"${if (fortress) "FORTRESS" else "FIELD"}","tileset":0,"image":"maps/battle_${id.toString().padStart(3, '0')}_ts0.png","landEligible":${id !in 209..212},"terrainCounts":{$counts},"terrainRows":[$rows]}"""
        }
        return """{"schemaVersion":1,"boardSize":64,"status":"owner-accepted derived catalog","source":{"originalBinaryCommitted":false},"boards":[$boards]}"""
    }
}
